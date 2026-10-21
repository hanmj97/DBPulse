package com.dbpulse.persistence;

import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.dbpulse.domain.DeadlockEvent;
import com.dbpulse.domain.DeadlockParticipant;
import com.dbpulse.collector.MssqlWaitCollector.WaitSample;
import com.dbpulse.collector.MssqlBlockingChainCollector.BlockingSample;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.security.DisabledTargetCredentialStore;
import com.dbpulse.service.LocalTargetService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite isolated metadata parity smoke. NEVER connects to a production DB
 * or changes the existing PostgreSQL history. This is NOT a migration test.
 */
class SqliteMetadataCompatibilityTest {
    @Test void tableSchemaAndFullMetadataRepositoryRoundtrip() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("schema-sqlite.sql"));
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            String target = UUID.randomUUID().toString();
            Instant now = Instant.parse("2026-10-21T02:00:00Z");

            // Original UUIDs are immutable identifiers for historical samples.
            jdbc.update("""
                INSERT INTO local_target_registry
                (id, display_name, server_host, server_port, database_name,
                 login_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE', ?)
                """, target, "Local Lab", "127.0.0.1", 1433, "DBPulseLab",
                "dbpulse_monitor", Timestamp.from(now));
            var registry = new LocalTargetService(jdbc,true,false,
                "localhost,127.0.0.1",new MssqlTargetProperties(),
                new DisabledTargetCredentialStore());
            assertEquals(1,registry.list().size());
            assertEquals(target,registry.list().getFirst().id());

            var metric = new MetricSnapshot(now, 4, 2, 1, 550, 34);
            var metrics = new MetricRepository(jdbc);
            metrics.save(target,metric);
            assertEquals(metric,metrics.history(target,10).getFirst());

            var incidents = new IncidentRepository(jdbc);
            incidents.upsert(target,"BLOCKING",now,2,"Blocking observed");
            incidents.upsert(target,"BLOCKING",now.plusSeconds(2),3,"Blocking observed");
            assertEquals(1, incidents.latest(target,20).size());
            assertEquals(3,incidents.latest(target,20).getFirst().observedValue());
            incidents.resolve(target,"BLOCKING",now.plusSeconds(3));
            assertEquals("RESOLVED",incidents.latest(target,20).getFirst().status());

            var queries = new QueryPerformanceRepository(jdbc);
            var query = new QueryPerformanceSnapshot(now,100,200,7,
                123.4,21.2,9.1,now,"SLOW_QUERY","SELECT");
            queries.save(target,List.of(query));
            // Use clock-relative samples for freshness comparison.
            Instant current=Instant.now();
            queries.save(target,List.of(new QueryPerformanceSnapshot(
                current,101,201,1,2.0,1.0,1.0,current,"NORMAL","SELECT")));
            assertEquals(1,queries.latest(target,current,10).size());

            var blocking = new BlockingChainRepository(jdbc);
            long blockingId=blocking.save(target,new BlockingSample(now,List.of()));
            assertTrue(blockingId>0);
            assertEquals(0,blocking.edges(blockingId,target).size());

            var waits = new WaitRepository(jdbc);
            long waitId=waits.save(target,new WaitSample(now,List.of(),false));
            assertTrue(waitId>0);
            assertEquals(0,waits.readSnapshot(target,waitId).observations().size());

            var deadlocks = new DeadlockRepository(jdbc);
            var deadlock=new DeadlockEvent("a".repeat(64),now,
                List.of(new DeadlockParticipant(55,true),
                    new DeadlockParticipant(77,false)));
            assertEquals(1,deadlocks.saveNew(target,List.of(deadlock)));
            assertEquals(0,deadlocks.saveNew(target,List.of(deadlock)));
            assertEquals(2,deadlocks.history(target,10).getFirst().participants().size());

            // Foreign keys must be enabled: removing parent cascades its details.
            jdbc.update("DELETE FROM deadlock_events WHERE target_id = ?",target);
            assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM deadlock_participants",Integer.class));

            // Independent DB identity and chronology remain intact.
            assertEquals(target,registry.list().getFirst().id());
            assertEquals(1,metrics.history(target,10).size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM incidents",Integer.class));
        }
    }
}
