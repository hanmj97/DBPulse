"""Patch isolated DBPulse Java timestamp READ compatibility (no database changes).

The PG->SQLite staging import preserves microsecond UTC offsets, while sqlite-jdbc
getTimestamp only recognizes its default date format. Keep the original SQLite
records intact; parse ISO_OFFSET_DATE_TIME on read and fall back to the existing
JDBC date path for native SQL timestamps/PostgreSQL.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import sys
import uuid

FILES = {
    "service/LocalTargetService.java": 1,
    "persistence/MetricRepository.java": 1,
    "persistence/QueryPerformanceRepository.java": 2,
    "persistence/IncidentRepository.java": 4,
    "persistence/BlockingChainRepository.java": 1,
    "persistence/DeadlockRepository.java": 1,
    "persistence/WaitRepository.java": 2,
}
BASE = Path("backend/src/main/java/com/dbpulse")
HELPER = BASE / "persistence/ImportedTimestampReader.java"
TEST = Path("backend/src/test/java/com/dbpulse/persistence/ImportedTimestampReaderTest.java")
TIMESTAMP_PATTERN = re.compile(r'rs\.getTimestamp\("([a-z_]+)"\)\.toInstant\(\)')

HELPER_TEXT = '''package com.dbpulse.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Read historic PostgreSQL-to-SQLite staging timestamps without losing their
 * UTC offsets or microseconds. Postgres and native sqlite-jdbc timestamps
 * continue to use the original JDBC conversion path.
 *
 * This is a read compatibility layer ONLY. Mixed timestamp write formats,
 * retention cutoff comparisons and desktop SQLite cutover require separate
 * validation before any production switch.
 */
public final class ImportedTimestampReader {
    private ImportedTimestampReader() {}

    public static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant();
        } catch (DateTimeParseException ignored) {
            // Postgres and sqlite-jdbc's native timestamp strings use their
            // existing JDBC representation; preserve established behavior.
        }
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Instant value = instantOrNull(rs, column);
        if (value == null) throw new SQLException("DBPULSE_REQUIRED_TIMESTAMP_IS_NULL", "22007");
        return value;
    }
}
'''

TEST_TEXT = '''package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Mirrors the actual imported staging string without editing a user database. */
class ImportedTimestampReaderTest {
    @Test void importedOffsetMicrosecondsAndNativeJdbcTimestamps() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = db.createStatement()) {
            statement.executeUpdate("CREATE TABLE sample (t TIMESTAMP)");
            statement.executeUpdate("INSERT INTO sample(t) VALUES "
                + "('2026-10-21T04:04:56.524781+00:00'), "
                + "('2026-10-21T13:04:56.524781+09:00'), "
                + "('2026-10-21 04:04:56.524'), (NULL)");
            try (var rs = statement.executeQuery("SELECT t FROM sample ORDER BY rowid")) {
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524781Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524781Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-10-21T04:04:56.524Z"),
                    ImportedTimestampReader.instant(rs, "t"));
                assertTrue(rs.next());
                assertNull(ImportedTimestampReader.instantOrNull(rs, "t"));
                assertThrows(java.sql.SQLException.class,
                    () -> ImportedTimestampReader.instant(rs, "t"));
            }
        }
    }
}
'''

class PatchError(RuntimeError):
    pass


def transform(rel: str, original: str, expected: int) -> str:
    if "ImportedTimestampReader" in original:
        raise PatchError(f"already patched: {rel}")
    revised, hits = TIMESTAMP_PATTERN.subn(
        lambda m: f'ImportedTimestampReader.instant(rs, "{m.group(1)}")', original
    )
    if hits != expected:
        raise PatchError(f"unexpected timestamp locations in {rel}: {hits} (expected {expected})")
    if rel == "persistence/IncidentRepository.java":
        src = 'Timestamp resolved = rs.getTimestamp("resolved_at");'
        dst = 'Instant resolved = ImportedTimestampReader.instantOrNull(rs, "resolved_at");'
        if revised.count(src) != 2 or revised.count("resolved == null ? null : resolved.toInstant()") != 2:
            raise PatchError("unexpected nullable incident timestamps")
        revised = revised.replace(src, dst)
        revised = revised.replace('resolved == null ? null : resolved.toInstant()', 'resolved')
    if rel.startswith("service/"):
        anchor = "import com.dbpulse.config.MssqlTargetProperties;"
        if revised.count(anchor) != 1:
            raise PatchError("service imports changed")
        revised = revised.replace(anchor, anchor + "\nimport com.dbpulse.persistence.ImportedTimestampReader;")
    return revised


def plan(root: Path) -> dict[Path, str]:
    updates = {}
    for rel, expected in FILES.items():
        path = root / BASE / rel
        if not path.is_file():
            raise PatchError(f"required file missing: {path}")
        updates[path] = transform(rel, path.read_text(encoding="utf-8-sig"), expected)
    if (root / HELPER).exists() or (root / TEST).exists():
        raise PatchError("helper/test already exists; do not overwrite an existing implementation")
    updates[root / HELPER] = HELPER_TEXT
    updates[root / TEST] = TEST_TEXT
    return updates


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path.cwd())
    parser.add_argument("--apply", action="store_true", help="apply only after a successful dry run")
    args = parser.parse_args(argv)
    root = args.project_root.resolve()
    try:
        updates = plan(root)
        print("SQLITE_TIMESTAMP_PATCH_VALIDATED", len(updates), "files")
        if not args.apply:
            print("DRY_RUN_ONLY_NO_FILES_CHANGED")
            return 0
        # Back up originals outside the tracked source tree before mutating anything.
        localappdata = os.environ.get("LOCALAPPDATA")
        backup_base = (Path(localappdata) / "DBPulse" / "patch-backups"
                       if localappdata else root / "_dbpulse_patch_backups")
        backup_dir = backup_base / ("sqlite-ts-read-" + uuid.uuid4().hex)
        backup_dir.mkdir(parents=True, exist_ok=False)
        for path in updates:
            if path.is_file():
                dest = backup_dir / path.relative_to(root)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, dest)
        for path, content in updates.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
        print("SQLITE_TIMESTAMP_PATCH_APPLIED")
        print("ORIGINAL_JAVA_FILES_BACKED_UP:", backup_dir)
        print("NO_SQLITE_OR_POSTGRESQL_DATA_MODIFIED")
        return 0
    except (PatchError, OSError) as error:
        print("SQLITE_TIMESTAMP_PATCH_STOPPED:", error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
