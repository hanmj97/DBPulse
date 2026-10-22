"""DBPulse Phase 15: normalize new SQLite metadata writes to imported UTC ISO timestamps.

Safely patches exactly known Java source locations. Dry run by default. Existing
SQLite/PostgreSQL databases are NEVER opened, copied or modified by this script.
The existing ImportedTimestampReader from the prior patch is mandatory.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import os
import re
import shutil
import sys
import uuid

BASE = Path('backend/src/main/java/com/dbpulse')
HELPER = BASE / 'persistence/MetadataTimestampValue.java'
TEST = Path('backend/src/test/java/com/dbpulse/persistence/SqliteMixedHistoryRegressionTest.java')
FILES = {
 'persistence/MetricRepository.java':1,
 'persistence/QueryPerformanceRepository.java':1,
 'persistence/IncidentRepository.java':1,
 'persistence/BlockingChainRepository.java':1,
 'persistence/WaitRepository.java':1,
 'persistence/DeadlockRepository.java':1,
 'service/LocalTargetService.java':1,
 'persistence/IncidentEvidenceRepository.java':1,
 'persistence/IncidentWaitEvidenceRepository.java':1,
}

HELPER_TEXT = '''package com.dbpulse.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL retains its normal JDBC TIMESTAMPTZ binding. Only SQLite stores
 * all NEW metadata timestamps in the same UTC ISO-8601 text family as the
 * exact-offset rows imported from PostgreSQL (Phase 15 staged migration).
 * Comparisons and indexes over ISO UTC TEXT then work on historic/new rows.
 * The original imported file is never mutated by this codec.
 */
public final class MetadataTimestampValue {
    private static final DateTimeFormatter SQLITE_UTC = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
        .appendLiteral("+00:00")
        .toFormatter(Locale.ROOT).withZone(ZoneOffset.UTC);

    private MetadataTimestampValue() {}

    public static boolean isSqlite(JdbcTemplate jdbc) {
        Boolean result = jdbc.execute((ConnectionCallback<Boolean>) connection ->
            "SQLite".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()));
        if (result == null) throw new IllegalStateException("DBPULSE_METADATA_DATABASE_UNKNOWN");
        return result;
    }

    public static Object value(boolean sqlite, Instant instant) {
        if (instant == null) throw new IllegalArgumentException("METADATA_TIMESTAMP_REQUIRED");
        return sqlite ? SQLITE_UTC.format(instant) : Timestamp.from(instant);
    }
}
'''
TEST_TEXT = '''package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.security.DisabledTargetCredentialStore;
import com.dbpulse.service.LocalTargetService;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * No PostgreSQL/SQL Server connection; no real user SQLite file touched.
 * Simulates imported ISO rows plus live Java repository writes on a fresh DB.
 */
class SqliteMixedHistoryRegressionTest {
    @Test void historicRowsAndNewWritesKeepChronologyAndQueryStoreEquality() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("schema-sqlite.sql"));
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(db, true));
            String target = UUID.randomUUID().toString();
            Instant first = Instant.parse("2026-10-21T04:04:56.524781Z");
            Instant second = first.plus(1, ChronoUnit.MICROS);
            assertTrue(MetadataTimestampValue.isSqlite(jdbc));
            assertEquals("2026-10-21T04:04:56.524782+00:00",
                MetadataTimestampValue.value(true, second));
            assertInstanceOf(Timestamp.class, MetadataTimestampValue.value(false, second));

            jdbc.update("""
                INSERT INTO metric_snapshots (target_id, collected_at, active_requests,
                    blocked_requests, long_running_requests, max_elapsed_ms, total_cpu_ms)
                VALUES (?, '2026-10-21T04:04:56.524781+00:00', 1, 0, 0, 10, 5)
                """, target);
            MetricRepository metrics = new MetricRepository(jdbc);
            metrics.save(target, new MetricSnapshot(second, 2, 0, 0, 11, 6));
            var metricHistory = metrics.history(target, 10);
            assertEquals(2, metricHistory.size());
            assertEquals(first, metricHistory.get(0).collectedAt());
            assertEquals(second, metricHistory.get(1).collectedAt());

            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var q = new QueryPerformanceSnapshot(now, 9, 8, 10, 5.1,
                3.2, 2.3, now, "NORMAL", "SELECT");
            QueryPerformanceRepository queries = new QueryPerformanceRepository(jdbc);
            queries.save(target, List.of(q));
            assertEquals(1, queries.latest(target, now, 10).size());
            assertEquals(now, queries.latest(target, now, 10).getFirst().lastExecutedAt());

            var incidents = new IncidentRepository(jdbc);
            incidents.upsert(target, "BLOCKING", first, 3, "test incident");
            incidents.resolve(target, "BLOCKING", second);
            IncidentRecord incident = incidents.latest(target, 10).getFirst();
            assertEquals(first, incident.startedAt());
            assertEquals(second, incident.resolvedAt());

            var block = new BlockingChainRepository(jdbc);
            long blockId = block.save(target,
                new com.dbpulse.collector.MssqlBlockingChainCollector.BlockingSample(second, List.of()));
            assertTrue(blockId > 0);
            var wait = new WaitRepository(jdbc);
            long waitId = wait.save(target,
                new com.dbpulse.collector.MssqlWaitCollector.WaitSample(second, List.of(), false));
            assertEquals(second, wait.readSnapshot(target, waitId).collectedAt());
            assertTrue(jdbc.query("PRAGMA foreign_key_check", (rs, row) -> 1).isEmpty());
        }
    }

    @Test void evidenceWindowAndOlderThanPruningUseIsoUtcBoundaries() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("schema-sqlite.sql"));
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(db, true));
            String target = UUID.randomUUID().toString();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Instant old = now.minus(10, ChronoUnit.DAYS);
            Instant current = now.minus(1, ChronoUnit.HOURS);
            MetricRepository metrics = new MetricRepository(jdbc);
            metrics.save(target, new MetricSnapshot(old, 1, 0, 0, 1, 1));
            metrics.save(target, new MetricSnapshot(current, 2, 1, 0, 1, 1));
            var ev = new IncidentEvidenceRepository(jdbc)
                .read(target, now.minus(2, ChronoUnit.HOURS), now);
            assertEquals(1, ev.metrics().samples());
            assertEquals(1, ev.metrics().peakBlocked());
            var queries = new QueryPerformanceRepository(jdbc);
            queries.save(target, List.of(new QueryPerformanceSnapshot(old, 1, 1, 1,
                1.0, 1.0, 1.0, old, "NORMAL", "SELECT")));
            queries.save(target, List.of(new QueryPerformanceSnapshot(current, 2, 2, 1,
                1.0, 1.0, 1.0, current, "NORMAL", "SELECT")));
            assertEquals(1, queries.purgeOlderThanSevenDays());
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_performance_snapshots", Integer.class));
            assertEquals(0, new IncidentWaitEvidenceRepository(jdbc)
                .read(target, now.minus(2, ChronoUnit.HOURS), now).sampleCount());
        }
    }
}
'''

class PatchError(RuntimeError):
    pass

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count=text.count(old)
    if count!=1:
        raise PatchError(f"{label}: expected exactly one anchor, got {count}")
    return text.replace(old,new)

def timestamp_change(content: str, old: str, new: str, label: str) -> str:
    if old not in content:
        raise PatchError(f"{label}: missing timestamp anchor")
    return content.replace(old,new)

def transform(rel: str, s: str) -> str:
    if 'MetadataTimestampValue' in s:
        raise PatchError(f'{rel}: already patched or overlapping edits')
    # 7 writers + 2 time-window readers. The prior timestamp read-patch MUST exist.
    if rel.endswith('LocalTargetService.java'):
        if 'ImportedTimestampReader.instant(rs, "created_at")' not in s:
            raise PatchError('prior SQLite read patch missing from LocalTargetService')
        s=replace_once(s, 'import com.dbpulse.persistence.ImportedTimestampReader;',
            'import com.dbpulse.persistence.ImportedTimestampReader;\nimport com.dbpulse.persistence.MetadataTimestampValue;', rel)
    elif rel.endswith('IncidentEvidenceRepository.java') or rel.endswith('IncidentWaitEvidenceRepository.java'):
        s=replace_once(s,'import java.sql.Timestamp;', 'import com.dbpulse.persistence.ImportedTimestampReader;\nimport com.dbpulse.persistence.MetadataTimestampValue;',rel)
        s=s.replace('Timestamp begin = Timestamp.from(from);', 'Object begin = MetadataTimestampValue.value(sqlite, from);')
        s=s.replace('Timestamp end = Timestamp.from(to);', 'Object end = MetadataTimestampValue.value(sqlite, to);')
        if s.count('Object begin =') != 1 or s.count('Object end =') != 1:
            raise PatchError(f'{rel}: evidence time window pattern mismatch')
        s=s.replace('rs.getTimestamp("collected_at").toInstant()', 'ImportedTimestampReader.instant(rs, "collected_at")')
        s=s.replace('rs.getTimestamp("occurred_at").toInstant()', 'ImportedTimestampReader.instant(rs, "occurred_at")')
        if 'rs.getTimestamp(' in s:
            raise PatchError(f'{rel}: unpatched timestamp reader')
    else:
        if 'ImportedTimestampReader.instant' not in s and rel not in ('persistence/DeadlockRepository.java',):
            raise PatchError(f'{rel}: previously fixed imported timestamp reads required')
        # Classes in same persistence package don't need imports.
        pass

    # Lazy dialect lookup: existing mock/new JdbcTemplate tests never open a DB
    # until an actual timestamp operation needs one (critical regression guard).
    lazy = """    private volatile Boolean sqliteDialect;
    private boolean sqlite() {
        Boolean value = sqliteDialect;
        if (value == null) {
            value = MetadataTimestampValue.isSqlite(jdbc);
            sqliteDialect = value;
        }
        return value;
    }
"""
    s=replace_once(s,'    private final JdbcTemplate jdbc;',
       '    private final JdbcTemplate jdbc;\n' + lazy,rel)
    # Keep constructors/Mockito unit tests unchanged.
    s=s.replace('MetadataTimestampValue.value(sqlite,', 'MetadataTimestampValue.value(sqlite(),')

    if rel.endswith('LocalTargetService.java'):
        pattern = "(id, display_name, server_host, server_port, database_name, login_name, status)"
        s=s.replace(pattern, "(id, display_name, server_host, server_port, database_name, login_name, status, created_at)")
        s=s.replace("login_name, status)\n                     VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')",
                    "login_name, status, created_at)\n                     VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE', ?)")
        s=s.replace("VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')", "VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE', ?)")
        s=replace_once(s, 'details.port(), details.databaseName().trim(), details.username().trim());',
            'details.port(), details.databaseName().trim(), details.username().trim(),\n                     MetadataTimestampValue.value(sqlite, Instant.now()));',rel)
        s=replace_once(s, 'details.databaseName().trim(), details.username().trim());',
            'details.databaseName().trim(), details.username().trim(),\n                 MetadataTimestampValue.value(sqlite, Instant.now()));', rel)
        if s.count('INSERT INTO local_target_registry')!=2 or s.count("'REGISTERED_INACTIVE', ?)")!=2:
            raise PatchError('LocalTargetService registration timestamp mismatch')
    elif rel.endswith('QueryPerformanceRepository.java'):
        s=replace_once(s,'ps.setTimestamp(2, Timestamp.from(row.collectedAt()));',
            'ps.setObject(2, MetadataTimestampValue.value(sqlite, row.collectedAt()));',rel)
        s=replace_once(s,'ps.setTimestamp(9, Timestamp.from(row.lastExecutedAt()));',
            'ps.setObject(9, MetadataTimestampValue.value(sqlite, row.lastExecutedAt()));',rel)
        s=replace_once(s, 'Timestamp.from(java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS))',
            'MetadataTimestampValue.value(sqlite, java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS))',rel)
        s=replace_once(s,'targetId, Timestamp.from(sampledAt),',
            'targetId, MetadataTimestampValue.value(sqlite, sampledAt),',rel)
        s=replace_once(s,'Timestamp.from(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.MINUTES))',
            'MetadataTimestampValue.value(sqlite, java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.MINUTES))',rel)
    elif rel.endswith('MetricRepository.java'):
        s=replace_once(s,'targetId, Timestamp.from(item.collectedAt()),',
            'targetId, MetadataTimestampValue.value(sqlite, item.collectedAt()),',rel)
    elif rel.endswith('IncidentRepository.java'):
        s=s.replace('Timestamp.from(at)', 'MetadataTimestampValue.value(sqlite, at)')
        if s.count('MetadataTimestampValue.value(sqlite, at)')!=3:
            raise PatchError('incident timestamps count mismatch')
    elif rel.endswith('BlockingChainRepository.java'):
        s=replace_once(s, 'targetId, Timestamp.from(sample.collectedAt()), sample.edges().size()',
            'targetId, MetadataTimestampValue.value(sqlite, sample.collectedAt()), sample.edges().size()',rel)
        s=replace_once(s, 'Timestamp.from(java.time.Instant.now().minus(7,',
            'MetadataTimestampValue.value(sqlite, java.time.Instant.now().minus(7,',rel)
    elif rel.endswith('WaitRepository.java'):
        s=replace_once(s, 'targetId, Timestamp.from(sample.collectedAt()),',
            'targetId, MetadataTimestampValue.value(sqlite, sample.collectedAt()),',rel)
        s=replace_once(s, 'Timestamp.from(java.time.Instant.now().minus(7,',
            'MetadataTimestampValue.value(sqlite, java.time.Instant.now().minus(7,',rel)
    elif rel.endswith('DeadlockRepository.java'):
        s=replace_once(s, 'targetId, event.eventKey(), Timestamp.from(event.occurredAt()),',
            'targetId, event.eventKey(), MetadataTimestampValue.value(sqlite, event.occurredAt()),',rel)
    # All newly inserted timestamp calls should lazily resolve the dialect.
    s=s.replace('MetadataTimestampValue.value(sqlite,', 'MetadataTimestampValue.value(sqlite(),')
    return s

def plan(root: Path) -> dict[Path,str]:
    if not (root / BASE / 'persistence/ImportedTimestampReader.java').is_file():
        raise PatchError('apply previous timestamp read patch and rebuild first')
    out={}
    for rel in FILES:
        path=root/BASE/rel
        if not path.is_file():
            raise PatchError(f'required source file missing: {path}')
        out[path]=transform(rel,path.read_text(encoding='utf-8-sig'))
    for path, body in ((root/HELPER,HELPER_TEXT),(root/TEST,TEST_TEXT)):
        if path.exists():
            raise PatchError(f'new file already exists: {path}')
        out[path]=body
    return out

def main(argv=None)->int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root',type=Path,default=Path.cwd())
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args(argv)
    root=args.project_root.resolve()
    try:
        changes=plan(root)
        print('SQLITE_LIVE_TIME_PATCH_PLAN_VALIDATED',len(changes))
        if not args.apply:
            print('DRY_RUN_NO_FILES_CHANGED');return 0
        home=Path(os.environ.get('LOCALAPPDATA') or root/'_dbpulse_patch_backups')
        base=home/'DBPulse'/'patch-backups'/('sqlite-live-time-'+uuid.uuid4().hex)
        base.mkdir(parents=True,exist_ok=False)
        for path in changes:
            if path.is_file():
                dest=base/path.relative_to(root)
                dest.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(path,dest)
        for path,updated in changes.items():
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(updated,encoding='utf-8',newline='\n')
        print('SQLITE_LIVE_TIME_PATCH_APPLIED')
        print('SOURCE_BACKUPS:',base)
        print('NO_POSTGRES_OR_SQLITE_USER_DATA_MODIFIED')
        return 0
    except (PatchError,OSError) as exc:
        print('SQLITE_LIVE_TIME_PATCH_STOPPED:',exc,file=sys.stderr)
        return 2

if __name__=='__main__':
    raise SystemExit(main())
