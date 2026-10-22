#!/usr/bin/env python3
"""Add a narrowly scoped 18082 loopback Origin for SQLite live LAB browser POSTs.

Dry run by default. Patches only LocalTargetController.java; never opens a DB.
"""
import argparse
import os
from pathlib import Path
import shutil
import sys
import uuid

FILE = Path('backend/src/main/java/com/dbpulse/api/LocalTargetController.java')
OLD = '"http://127.0.0.1:18081", "http://localhost:18081");'
NEW = ('"http://127.0.0.1:18081", "http://localhost:18081",\n'
       '        "http://127.0.0.1:18082", "http://localhost:18082");')


def updated(original: str) -> str:
    if NEW in original:
        raise ValueError('SQLITE_LIVE_ORIGIN_ALREADY_PATCHED')
    if original.count(OLD) != 1:
        raise ValueError('SQLITE_LIVE_ORIGIN_EXPECTED_SOURCE_MISMATCH')
    return original.replace(OLD, NEW, 1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--project-root', default='.', type=Path)
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args(argv)
    root = args.project_root.resolve()
    target = root / FILE
    try:
        if not target.is_file() or target.is_symlink():
            raise ValueError('SOURCE_JAVA_FILE_INVALID')
        text = target.read_text(encoding='utf-8-sig')
        result = updated(text)
        if not args.apply:
            print('SQLITE_LIVE_ORIGIN_PATCH_DRY_RUN_OK')
            return 0
        parent = Path(os.environ.get('LOCALAPPDATA') or root / '_local_patch_backup')
        backup_dir = parent / 'DBPulse' / 'patch-backups' / ('sqlite-live-origin-'+uuid.uuid4().hex)
        dest = backup_dir / FILE
        dest.parent.mkdir(parents=True, exist_ok=False)
        shutil.copy2(target, dest)
        target.write_text(result, encoding='utf-8')
        print('SQLITE_LIVE_ORIGIN_PATCH_APPLIED')
        print('SOURCE_BACKUP:', dest)
        print('NO_DATABASE_FILES_CHANGED')
        return 0
    except (OSError, ValueError) as exc:
        print('SQLITE_LIVE_ORIGIN_PATCH_STOPPED:', exc, file=sys.stderr)
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
