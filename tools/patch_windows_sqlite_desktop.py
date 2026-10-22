"""Surgical, fail-closed WindowsDesktopBoot opt-in patch; supports Windows CRLF.

Does not rewrite the whole configure method: preserves unrelated local changes.
Dry-run is the default. The original Java file is backed up before --apply.
"""
import argparse
import re
from pathlib import Path

METHOD_PATTERN = re.compile(
    r'(?m)^[ \t]*public[ \t]+static[ \t]+void[ \t]+configure'
    r'[ \t]*\([ \t]*SpringApplication[ \t]+springApp[ \t]*\)[ \t]*\{'
)
PASSWORD_LINE = re.compile(r'(?m)^[ \t]*String[ \t]+metadataPassword[ \t]*=' 
                           r'[ \t]*loadMetadataPassword\(\)[ \t]*;[ \t]*\n')
PG_LINES = '''        props.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5544/dbpulse");
        props.put("spring.datasource.username","dbpulse");
        props.put("spring.datasource.password",metadataPassword);'''
SQLITE_SWITCH = '''        // Explicit local opt-in; an invalid marker or file fails before boot.
        // Without the marker, the historical PostgreSQL mode is unchanged.
        Path dbpulseDirectory = directory().getParent();
        if (DesktopSqliteStorage.isOptedIn(dbpulseDirectory)) {
            DesktopSqliteStorage.configure(dbpulseDirectory, props);
        } else {
            String metadataPassword=loadMetadataPassword();
            props.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5544/dbpulse");
            props.put("spring.datasource.username","dbpulse");
            props.put("spring.datasource.password",metadataPassword);
        }'''


def _method_end(text: str, opening_brace: int) -> int:
    """Find method closing brace, skipping Java strings and comments."""
    depth = 0
    i = opening_brace
    state = 'code'
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ''
        if state == 'code':
            if c == '/' and n == '/':
                state = 'line_comment'; i += 2; continue
            if c == '/' and n == '*':
                state = 'block_comment'; i += 2; continue
            if c == '"':
                state = 'string'; i += 1; continue
            if c == "'":
                state = 'char'; i += 1; continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return i + 1
        elif state == 'line_comment':
            if c == '\n':
                state = 'code'
        elif state == 'block_comment':
            if c == '*' and n == '/':
                state = 'code'; i += 2; continue
        elif state in ('string', 'char'):
            if c == '\\':
                i += 2; continue
            if c == ('"' if state == 'string' else "'"):
                state = 'code'
        i += 1
    raise ValueError('WINDOWS_BOOT_METHOD_UNTERMINATED')


def _normalized_and_style(source: str):
    # A Windows Git checkout can have CRLF despite the GitHub file using LF.
    if '\r' in source.replace('\r\n', ''):
        raise ValueError('WINDOWS_BOOT_UNSUPPORTED_LINE_ENDINGS')
    newline = '\r\n' if '\r\n' in source else '\n'
    normalized = source.replace('\r\n', '\n')
    return normalized, newline


def replace_method(source: str) -> str:
    normalized, newline = _normalized_and_style(source)
    found = list(METHOD_PATTERN.finditer(normalized))
    if len(found) != 1:
        raise ValueError('WINDOWS_BOOT_METHOD_SIGNATURE_MISMATCH')
    method_start = found[0].start()
    method_end = _method_end(normalized, found[0].end() - 1)
    method = normalized[method_start:method_end]

    if 'DesktopSqliteStorage.isOptedIn' in method:
        raise ValueError('PATCH_ALREADY_APPLIED')
    if len(PASSWORD_LINE.findall(method)) != 1 or method.count(PG_LINES) != 1:
        raise ValueError('WINDOWS_BOOT_SOURCE_UNEXPECTED')
    if 'Map<String,Object> props=new HashMap<>();' not in method:
        raise ValueError('WINDOWS_BOOT_PROPS_NOT_FOUND')
    # Surgical replacement: all unrelated code in configure stays intact.
    altered = PASSWORD_LINE.sub('', method, count=1).replace(PG_LINES, SQLITE_SWITCH, 1)
    result = normalized[:method_start] + altered + normalized[method_end:]
    return result.replace('\n', newline) if newline == '\r\n' else result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', type=Path, default=Path('.'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    target = args.project_root.resolve() / 'backend/src/main/java/com/dbpulse/desktop/WindowsDesktopBoot.java'
    if not target.is_file():
        raise SystemExit('WINDOWS_BOOT_SOURCE_NOT_FOUND')
    raw = target.read_bytes()
    bom = b'\xef\xbb\xbf'
    has_bom = raw.startswith(bom)
    try:
        source = raw[len(bom):].decode('utf-8') if has_bom else raw.decode('utf-8')
        new = replace_method(source)
    except ValueError as e:
        raise SystemExit('SQLITE_DESKTOP_PATCH_STOPPED: ' + str(e)) from None
    if not args.apply:
        print('DBPULSE_DESKTOP_SQLITE_PATCH_PREFLIGHT_OK')
        print('JAVA_LINE_ENDINGS: ' + ('CRLF' if '\r\n' in source else 'LF'))
        print('NO_JAVA_SOURCE_MODIFIED')
        return 0
    backup = target.with_suffix('.java.phase15-sqlite-original.bak')
    if backup.exists():
        raise SystemExit('PATCH_BACKUP_ALREADY_EXISTS_STOP')
    backup.write_bytes(raw)
    try:
        tmp = target.with_suffix('.java.phase15-sqlite.tmp')
        tmp.write_bytes((bom if has_bom else b'') + new.encode('utf-8'))
        tmp.replace(target)
    except Exception:
        target.write_bytes(raw)
        raise
    print('DBPULSE_DESKTOP_SQLITE_PATCH_APPLIED')
    print('BACKUP_SAVED: ' + str(backup))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
