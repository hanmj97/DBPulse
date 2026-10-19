"""Bounded local SQL Server synthetic load generator (one SQL batch per call)."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
import math
import random
import statistics
import threading
import time

import pyodbc
from common import connection_string

_thread = threading.local()


def worker_connection():
    conn = getattr(_thread, "conn", None)
    if conn is None:
        conn = pyodbc.connect(connection_string(), autocommit=False, timeout=5)
        # Server-side statement/query timeout in seconds.
        conn.timeout = 8
        _thread.conn = conn
    return conn


def execute_one(scenario):
    started = time.perf_counter()
    conn = None
    cur = None
    try:
        conn = worker_connection()
        cur = conn.cursor()
        choice = random.random()
        account = random.randint(1, 20000)
        if scenario == "blocking":
            if choice < 0.75:
                cur.execute("UPDATE dbo.Accounts SET balance = balance + 1 WHERE account_id = 1")
                kind = "hot_update"
            else:
                cur.execute("SELECT balance FROM dbo.Accounts WHERE account_id = ?", account)
                cur.fetchone()
                kind = "read"
        elif scenario == "scan":
            # Deliberately non-SARGable expression to demonstrate scan vs seek.
            cur.execute("SELECT COUNT_BIG(*) FROM dbo.Accounts WHERE account_id % 17 = ?", account % 17)
            cur.fetchone()
            kind = "scan"
        else:
            if choice < 0.75:
                cur.execute("SELECT balance FROM dbo.Accounts WHERE account_id = ?", account)
                cur.fetchone()
                kind = "read"
            else:
                cur.execute("UPDATE dbo.Accounts SET balance = balance + 1 WHERE account_id = ?", account)
                kind = "update"
        # Commit read-only transactions as well to release isolation state.
        conn.commit()
        return kind, (time.perf_counter() - started) * 1000, None
    except pyodbc.Error as exc:
        if conn is not None:
            try:
                conn.rollback()
            except pyodbc.Error:
                pass
            # Recreate the connection on the next attempt after a driver error.
            try:
                conn.close()
            except pyodbc.Error:
                pass
            _thread.conn = None
        return "failed", (time.perf_counter() - started) * 1000, str(exc)[:180]
    finally:
        if cur is not None:
            try:
                cur.close()
            except pyodbc.Error:
                pass


def percentile(values, percent):
    if not values:
        return 0.0
    values = sorted(values)
    position = math.ceil(len(values) * percent / 100) - 1
    return values[max(0, min(position, len(values) - 1))]


def main():
    parser = argparse.ArgumentParser(description="Local-only DBPulseLab load generator")
    parser.add_argument("--qps", type=int, default=150, help="target requests per second")
    parser.add_argument("--duration", type=int, default=10, help="scheduled seconds")
    parser.add_argument("--workers", type=int, default=16, help="maximum DB worker threads/connections")
    parser.add_argument("--scenario", choices=["normal", "scan", "blocking"], default="normal")
    args = parser.parse_args()
    if not (1 <= args.qps <= 300 and 1 <= args.duration <= 30 and 1 <= args.workers <= 32):
        parser.error("Safety limits: 1<=qps<=300, 1<=duration<=30, 1<=workers<=32")
    connection_string()  # Guard before generating any traffic.
    print(f"[DBPulse] scenario={args.scenario} target={args.qps} QPS x {args.duration}s; workers={args.workers}")
    print("[DBPulse] only DATABASE=DBPulseLab is permitted; one execute() call = one issued SQL batch")
    # Bound both concurrent execution and scheduled backlog.
    slots = threading.Semaphore(args.workers * 4)
    future_list = []
    dropped = 0
    count = args.qps * args.duration
    began = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        for tick in range(count):
            remaining = began + tick / args.qps - time.perf_counter()
            if remaining > 0:
                time.sleep(remaining)
            if not slots.acquire(blocking=False):
                dropped += 1
                continue
            try:
                future = executor.submit(execute_one, args.scenario)
                future.add_done_callback(lambda _: slots.release())
                future_list.append(future)
            except Exception:
                slots.release()
                raise
        scheduled_ended = time.perf_counter()
        results = [f.result() for f in as_completed(future_list)]
    ended = time.perf_counter()
    outcomes = Counter(item[0] for item in results)
    successes = [item[1] for item in results if item[0] != "failed"]
    errors = [item[2] for item in results if item[2]]
    scheduled_elapsed = scheduled_ended - began
    print("\n--- RESULTS ---")
    print(f"scheduled_calls={count}, issued_calls={len(future_list)}, dropped_due_to_backpressure={dropped}")
    print(f"success={len(successes)}, failed={outcomes['failed']}, operation_types={dict(outcomes)}")
    print(f"scheduling_elapsed={scheduled_elapsed:.2f}s, total_elapsed_including_drain={ended - began:.2f}s")
    print(f"issued_rate_during_schedule={len(future_list) / max(scheduled_elapsed, 0.001):.1f} calls/s")
    if successes:
        print(f"latency_ms: avg={statistics.mean(successes):.2f}, p50={percentile(successes, 50):.2f}, "
              f"p95={percentile(successes, 95):.2f}, p99={percentile(successes, 99):.2f}")
    if errors:
        print("first_error:", errors[0])
    print("NOTE: client-issued batches differ from server-wide Batch Requests/sec (which includes other sessions).")


if __name__ == "__main__":
    main()
