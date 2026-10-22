#!/usr/bin/env python3
"""Read-only offline/online count audit for DBPulse's isolated SQLite LIVE lab.

Only captures row counts and MAX row IDs, not database contents or secrets.
Never opens the original staging file for writing. Capture before enabling
monitoring and verify only after cleanly stopping Java.
"""
from __future__ import annotations
import argparse
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import sys

TABLES = (
    'local_target_registry','metric_snapshots','query_performance_snapshots',
    'incidents','blocking_chain_samples','blocking_chain_edges',
    'deadlock_events','deadlock_participants','wait_samples','wait_observations',
)
WITH_ID = tuple(t for t in TABLES if t not in ('deadlock_participants','local_target_registry'))
REQUIRED = ('metric_snapshots','blocking_chain_samples','wait_samples')
MAX_BASELINE_SIZE = 16384


def inspect(path: Path):
    if (not path.is_file() or path.is_symlink() or path.suffix.lower() != '.sqlite'
            or path.name.startswith('dbpulse-pg-stage-')
            or 'preview' not in path.parts):
        raise ValueError('NOT_A_PREVIEW_SQLITE_FILE')
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)) as db:
        db.execute('PRAGMA query_only=ON')
        db.execute('PRAGMA foreign_keys=ON')
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('SQLITE_INTEGRITY_CHECK_FAILED')
        if db.execute('PRAGMA foreign_key_check').fetchone() is not None:
            raise ValueError('SQLITE_FK_CHECK_FAILED')
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not set(TABLES).issubset(tables):
            raise ValueError('METADATA_TABLE_MISSING')
        counts={name: db.execute('SELECT COUNT(*) FROM "'+name+'"').fetchone()[0] for name in TABLES}
        maxids={name: db.execute('SELECT COALESCE(MAX(id),0) FROM "'+name+'"').fetchone()[0] for name in WITH_ID}
        return counts,maxids


def capture(path: Path, output: Path):
    if output.exists():
        raise ValueError('BASELINE_WOULD_OVERWRITE')
    counts,maxids=inspect(path)
    if sum(counts.values())==0:
        raise ValueError('SQLITE_UNEXPECTED_EMPTY')
    baseline={'format':'DBPulse-SQLite-live-lab-v1','file':str(path.resolve()),
              'captured_utc':datetime.now(timezone.utc).isoformat(),
              'counts':counts,'maxids':maxids}
    # This is non-secret metadata but still keep the report in user's local folder.
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8') as stream:
        json.dump(baseline,stream,ensure_ascii=False,indent=2)
    print('SQLITE_LIVE_BASELINE_CAPTURED')
    print('BASELINE_TOTAL_METADATA_ROWS:',sum(counts.values()))
    print('BASELINE_REPORT:',output)


def verify(path: Path, report: Path):
    if not report.is_file() or report.stat().st_size > MAX_BASELINE_SIZE:
        raise ValueError('BASELINE_INVALID')
    data=json.loads(report.read_text(encoding='utf-8'))
    if data.get('format')!='DBPulse-SQLite-live-lab-v1' or data.get('file')!=str(path.resolve()):
        raise ValueError('BASELINE_FILE_IDENTITY_MISMATCH')
    if set(data.get('counts',{}))!=set(TABLES) or set(data.get('maxids',{}))!=set(WITH_ID):
        raise ValueError('BASELINE_TABLES_INVALID')
    counts,maxids=inspect(path)
    newids={t:maxids[t]-data['maxids'][t] for t in WITH_ID}
    for t in REQUIRED:
        print(t+'_NEW_ID_DELTA:',newids[t])
    print('BEFORE_TOTAL_METADATA_ROWS:',sum(data['counts'].values()))
    print('AFTER_TOTAL_METADATA_ROWS:',sum(counts.values()))
    if any(newids[t]<=0 for t in REQUIRED):
        raise ValueError('COLLECTOR_DID_NOT_APPEND_REQUIRED_SQLITE_SAMPLES')
    # New IDs do not prove number of records (gaps possible); verify actual rows.
    with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        for table in REQUIRED:
            start=data['maxids'][table]
            records=db.execute('SELECT collected_at FROM "'+table+'" WHERE id>?', (start,)).fetchall()
            if len(records)==0:
                raise ValueError('NO_NEW_ROWS_'+table)
            for (timestamp,) in records:
                if not isinstance(timestamp,str) or not timestamp.endswith('+00:00') or 'T' not in timestamp:
                    raise ValueError('NEW_DATE_FORMAT_INVALID_'+table)
                try:
                    dt=datetime.fromisoformat(timestamp)
                    if dt.utcoffset().total_seconds()!=0:
                        raise ValueError('NEW_DATE_OFFSET_INVALID_'+table)
                except (TypeError,ValueError) as e:
                    raise ValueError('NEW_DATE_FORMAT_INVALID_'+table) from e
            print(table+'_NEW_ROWS:',len(records))
    print('SQLITE_LIVE_COLLECTOR_INSERTS_AND_FK_VERIFIED')
    print('PG_AND_STAGING_ORIGINAL_NOT_TOUCHED_BY_THIS_AUDIT')


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    cmds=parser.add_subparsers(dest='command',required=True)
    for cmd in ('capture','verify'):
        sub=cmds.add_parser(cmd)
        sub.add_argument('--file',type=Path,required=True)
        sub.add_argument('--baseline',type=Path,required=True)
    args=parser.parse_args(argv)
    try:
        if args.command=='capture': capture(args.file,args.baseline)
        else: verify(args.file,args.baseline)
        return 0
    except (OSError,sqlite3.Error,ValueError,TypeError,KeyError,json.JSONDecodeError) as exc:
        label=str(exc) if isinstance(exc,ValueError) else 'SQLITE_LAB_AUDIT_FAILED'
        print('SQLITE_LIVE_AUDIT_STOPPED:',label,file=sys.stderr)
        return 2

if __name__=='__main__':
    raise SystemExit(main())
