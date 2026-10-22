"""Developer-only fail-closed opt-in of verified SQLite for Windows DBPulse.exe.

No PostgreSQL access, no passwords, no app process termination, and no source writes.
Rollback only disables the switch; the SQLite and PostgreSQL history remain.
"""
import argparse
import hashlib
import os
import sqlite3
import sys
import tempfile
from contextlib import closing
from pathlib import Path

TABLES = (
    'local_target_registry', 'metric_snapshots', 'query_performance_snapshots',
    'incidents', 'blocking_chain_samples', 'blocking_chain_edges',
    'deadlock_events', 'deadlock_participants', 'wait_samples', 'wait_observations',
)
MARKER = b'DBPULSE_SQLITE_DESKTOP_V1\n'


class Aborted(Exception):
    pass


def safe_regular(path):
    return path.is_file() and not path.is_symlink() and path.stat().st_size > 4096


def readonly(source):
    # URI mode=ro prevents even a missing file from being created by connect.
    return sqlite3.connect(source.resolve().as_uri() + '?mode=ro', uri=True)


def inventory(connection):
    if connection.execute('PRAGMA quick_check').fetchone() != ('ok',):
        raise Aborted('SQLITE_QUICK_CHECK_FAILED')
    if connection.execute('PRAGMA foreign_key_check').fetchone() is not None:
        raise Aborted('SQLITE_FOREIGN_KEY_CHECK_FAILED')
    existing = {r[0] for r in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if not set(TABLES).issubset(existing):
        raise Aborted('SQLITE_SCHEMA_NOT_COMPLETE')
    counts = {table: connection.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
              for table in TABLES}
    if counts['local_target_registry'] < 1 or counts['metric_snapshots'] < 1:
        raise Aborted('SQLITE_EXPECTED_HISTORY_MISSING')
    return counts


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def app_paths(app_data):
    home = app_data / 'DBPulse'
    return (home, home / 'data' / 'dbpulse.sqlite', home / 'desktop' / 'sqlite-mode.enabled')


def reject_symlink(path):
    if path.is_symlink():
        raise Aborted('SYMLINK_NOT_ALLOWED')


def ensure_parent(parent, home):
    for current in (home, parent):
        if current.exists():
            reject_symlink(current)
            if not current.is_dir():
                raise Aborted('INVALID_DESKTOP_DATA_FOLDER')
        else:
            current.mkdir(parents=True, exist_ok=True)


def activate(source, app_data):
    home, output, marker = app_paths(app_data)
    if marker.exists() or marker.is_symlink() or output.exists() or output.is_symlink():
        raise Aborted('SQLITE_ALREADY_PRESENT_NO_OVERWRITE')
    if not safe_regular(source):
        raise Aborted('SOURCE_SQLITE_NOT_FOUND_OR_TOO_SMALL')
    # Require choosing a former staging or preview file under LOCALAPPDATA/DBPulse/data.
    expected_root = (home / 'data').resolve()
    if source.resolve().parent.name not in ('staging', 'preview') or \
        source.resolve().parent.parent != expected_root or \
        source.suffix.lower() != '.sqlite':
        raise Aborted('SOURCE_NOT_VERIFIED_DBPULSE_STAGE_OR_PREVIEW')
    for candidate in (source, source.parent, expected_root):
        reject_symlink(candidate)
    for suffix in ('-wal', '-shm', '-journal'):
        if Path(str(source) + suffix).exists():
            raise Aborted('SOURCE_SQLITE_HAS_SIDECAR_STOP_AND_REVIEW')
    old_hash = digest(source)
    with closing(readonly(source)) as input_db:
        original = inventory(input_db)
        ensure_parent(output.parent, home)
        ensure_parent(marker.parent, home)
        # Temporary file in same directory allows atomic final promotion.
        fd, name = tempfile.mkstemp(dir=output.parent, prefix='.dbpulse-sqlite-', suffix='.tmp')
        os.close(fd)
        temporary = Path(name)
        try:
            with closing(sqlite3.connect(temporary)) as copied_db:
                input_db.backup(copied_db)
                copied_db.commit()
            with closing(readonly(temporary)) as verification_db:
                if inventory(verification_db) != original:
                    raise Aborted('SQLITE_COPY_COUNTS_MISMATCH')
            if digest(source) != old_hash:
                raise Aborted('SOURCE_CHANGED_DURING_COPY_RETRY_LATER')
            if output.exists() or marker.exists():
                raise Aborted('OUTPUT_WAS_CREATED_CONCURRENTLY')
            os.replace(temporary, output)
        finally:
            if temporary.exists():
                temporary.unlink()
    # Exact marker is written only AFTER final database validation/promotion.
    marker_fd, marker_name = tempfile.mkstemp(dir=marker.parent, prefix='.sqlite-switch-', suffix='.tmp')
    os.close(marker_fd)
    tmp_marker = Path(marker_name)
    try:
        tmp_marker.write_bytes(MARKER)
        if marker.exists():
            raise Aborted('SWITCH_CREATED_CONCURRENTLY')
        os.replace(tmp_marker, marker)
    finally:
        if tmp_marker.exists():
            tmp_marker.unlink()
    print('SQLITE_DESKTOP_OPT_IN_READY')
    print('TABLES_VERIFIED: 10')
    print('TOTAL_ROWS: ' + str(sum(original.values())))
    print('SQLITE_DB: ' + str(output))
    print('POSTGRESQL_SOURCE_AND_STAGE_UNCHANGED')
    print('NOW_BUILD_NEW_DBPULSE_EXE_AND_VERIFY_WITH_POSTGRES_STOPPED')


def rollback(app_data):
    _, output, marker = app_paths(app_data)
    if marker.is_symlink():
        raise Aborted('SYMLINK_NOT_ALLOWED')
    if not marker.is_file() or marker.read_bytes() != MARKER:
        raise Aborted('SQLITE_SWITCH_NOT_ENABLED_OR_INVALID')
    marker.unlink()
    print('SQLITE_DESKTOP_SWITCH_DISABLED')
    print('SQLITE_DATA_PRESERVED: ' + str(output))
    print('OLD_EXE_WILL_USE_POSTGRESQL_IF_AVAILABLE')


def status(app_data):
    _, output, marker = app_paths(app_data)
    print('SQLITE_DATA_FILE_EXISTS: ' + str(output.is_file()))
    print('SQLITE_SWITCH_EXISTS: ' + str(marker.is_file()))
    if marker.is_file():
        print('SQLITE_SWITCH_VALID: ' + str(not marker.is_symlink() and marker.read_bytes() == MARKER))
    if output.is_file() and not output.is_symlink():
        with closing(readonly(output)) as con:
            counts = inventory(con)
        print('SQLITE_TOTAL_ROWS: ' + str(sum(counts.values())))


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=('activate', 'rollback', 'status'))
    parser.add_argument('--source', type=Path, help='Explicit tested stage/preview SQLite file')
    parser.add_argument('--appdata', type=Path, help='TEST ONLY override; production uses Windows LOCALAPPDATA')
    args = parser.parse_args(argv)
    if not args.appdata and not os.getenv('LOCALAPPDATA'):
        print('WINDOWS_LOCALAPPDATA_NOT_AVAILABLE', file=sys.stderr)
        return 2
    app_data = args.appdata or Path(os.environ['LOCALAPPDATA'])
    try:
        if args.command == 'activate':
            if args.source is None:
                raise Aborted('EXPLICIT_SOURCE_REQUIRED')
            activate(args.source, app_data)
        elif args.command == 'rollback':
            rollback(app_data)
        else:
            status(app_data)
    except (Aborted, OSError, sqlite3.Error) as error:
        # Do not print sensitive path or arbitrary driver exception strings.
        label = str(error) if isinstance(error, Aborted) else 'SQLITE_DESKTOP_IO_OR_DB_ERROR'
        print('SQLITE_DESKTOP_STOPPED: ' + label, file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
