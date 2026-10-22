#!/usr/bin/env python3
"""Low-impact, read-only localhost API soak probe for a foreground session.

Samples only DBPulse health + target-status endpoints. Does NOT produce
workload or SQL, authenticate, retrieve query text, or store response bodies.
This is service liveness testing, not proof of negligible SQL Server overhead.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler, Request

BASE = "http://127.0.0.1:18081"
PATHS = ("/api/v1/health", "/api/v1/targets")
OPENER = build_opener(ProxyHandler({}))  # never proxy private localhost traffic


def sample() -> tuple[bool, float]:
    start = time.monotonic()
    try:
        for path in PATHS:
            with OPENER.open(Request(BASE + path, headers={"Accept": "application/json"}),
                             timeout=5) as response:
                if response.status != 200:
                    return False, (time.monotonic() - start) * 1000
                # Bounded parse only. No body displayed, stored or logged.
                document = json.loads(response.read(128 * 1024 + 1))
                if not isinstance(document, (dict, list)):
                    return False, (time.monotonic() - start) * 1000
        return True, (time.monotonic() - start) * 1000
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError):
        return False, (time.monotonic() - start) * 1000


def summarize(samples: list[tuple[bool, float]]) -> tuple[int, int, float, float]:
    ok = [ms for passed, ms in samples if passed]
    if not ok:
        return 0, len(samples), 0.0, 0.0
    ordered = sorted(ok)
    p95_index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1))
    return len(ok), len(samples)-len(ok), statistics.median(ok), ordered[p95_index]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Foreground localhost DBPulse API availability probe")
    parser.add_argument("--minutes", type=int, default=30,
                        help="1-1440 minutes; does not run as a background service")
    parser.add_argument("--interval-sec", type=int, default=60,
                        help="15-300 seconds; max one health+target request pair per interval")
    parser.add_argument("--once", action="store_true", help="One probe only, useful for testing")
    args = parser.parse_args(argv)
    if not 1 <= args.minutes <= 1440 or not 15 <= args.interval_sec <= 300:
        parser.error("minutes must be 1..1440 and interval-sec 15..300")

    samples = []
    count = 1 if args.once else math.ceil(args.minutes * 60 / args.interval_sec)
    next_tick = time.monotonic()
    try:
        for index in range(count):
            if index:
                time.sleep(max(0, next_tick - time.monotonic()))
            samples.append(sample())
            next_tick += args.interval_sec
    except KeyboardInterrupt:
        print("SOAK_PROBE_INTERRUPTED: partial sample summary only")
    good, bad, median, p95 = summarize(samples)
    print("DBPULSE_LOCALHOST_SOAK_SAMPLES: " + str(len(samples)))
    print("DBPULSE_LOCALHOST_OK: " + str(good))
    print("DBPULSE_LOCALHOST_FAILED: " + str(bad))
    print(f"DBPULSE_LOCALHOST_RESPONSE_MEDIAN_MS: {median:.1f}")
    print(f"DBPULSE_LOCALHOST_RESPONSE_P95_MS: {p95:.1f}")
    print("SQL_SERVER_COLLECTOR_OVERHEAD_NOT_MEASURED")
    return 0 if good > 0 and bad == 0 and len(samples) == count else 2


if __name__ == "__main__":
    raise SystemExit(main())
