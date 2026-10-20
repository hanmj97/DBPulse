package com.dbpulse.collector;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MssqlQueryStorePollingPolicyTest {
    private static String statement(String field) throws Exception {
        Field f = MssqlQueryStoreCollector.class.getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void stateQueryRefreshesOnFirstPollAndOnlyAfterFiveMinutes() {
        Instant now = Instant.parse("2026-10-20T02:00:00Z");
        assertThat(MssqlQueryStoreCollector.shouldRefreshState(null,now)).isTrue();
        assertThat(MssqlQueryStoreCollector.shouldRefreshState(now,now.plusSeconds(60))).isFalse();
        assertThat(MssqlQueryStoreCollector.shouldRefreshState(now,now.plusSeconds(299))).isFalse();
        assertThat(MssqlQueryStoreCollector.shouldRefreshState(now,now.plusSeconds(300))).isTrue();
    }

    @Test
    void collectorFirstAggregatesRecentRuntimeStatsBeforeTextClassification() throws Exception {
        String sql = statement("QUERY_STATS_SQL");
        assertThat(sql).contains(
            "WITH aggregates AS (",
            "FROM sys.query_store_plan p",
            "JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id",
            "rs.last_execution_time >= DATEADD(MINUTE, -15, SYSDATETIMEOFFSET())",
            "FROM aggregates a",
            "JOIN sys.query_store_query_text qt ON qt.query_text_id = q.query_text_id"
        );
        assertThat(sql.indexOf("FROM aggregates a"))
            .isGreaterThan(sql.indexOf("WHERE rs.last_execution_time"));
        assertThat(sql).contains("DBPULSE_INTERNAL_QSTORE_STATS_V1");
        assertThat(sql).doesNotContain("WHERE q.query_id IN (95, 72");
    }
}
