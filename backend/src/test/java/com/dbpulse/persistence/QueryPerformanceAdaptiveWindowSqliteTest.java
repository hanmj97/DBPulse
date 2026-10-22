package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;

/** Ensures adaptive Query Store polling does not erase 2-3 minute old samples.
 * The service/controller separately enforce freshness for "live" queries.
 */
class QueryPerformanceAdaptiveWindowSqliteTest {
    @Test void queryRowsRemainReadableAcrossAdaptivePollSkip() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(db, new ClassPathResource("schema-sqlite.sql"));
            var repository = new QueryPerformanceRepository(
                new JdbcTemplate(new SingleConnectionDataSource(db, true)));
            var target = UUID.randomUUID().toString();
            var collected = Instant.now().minusSeconds(135);
            var item = new QueryPerformanceSnapshot(collected, 17, 2, 4,
                8.0, 3.0, 10.0, collected, "OBSERVE", "OTHER_OR_UNKNOWN");
            repository.save(target, List.of(item));
            var found = repository.latest(target, collected, 20);
            assertEquals(1, found.size());
            assertEquals(17, found.getFirst().queryId());
            assertEquals(0, repository.latest("different-target", collected, 20).size());
        }
    }
}
