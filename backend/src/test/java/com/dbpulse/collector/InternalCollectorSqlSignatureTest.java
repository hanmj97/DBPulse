package com.dbpulse.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard: every currently shipped MSSQL collector statement
 * is identifiable independent of SQL Server Query ID and Plan ID.
 * An actual SQL Server integration test is still required because
 * Query Store may omit comments or normalize parameter placeholders.
 */
class InternalCollectorSqlSignatureTest {
    private record Statement(Class<?> type, String field, String label) {}

    private static final List<Statement> INTERNAL_SQL = List.of(
        new Statement(MssqlCollector.class, "METRIC_SQL", "METRICS"),
        new Statement(MssqlBlockingChainCollector.class, "SQL", "BLOCKING"),
        new Statement(MssqlWaitCollector.class, "SQL", "WAITS"),
        new Statement(MssqlDeadlockCollector.class, "SQL", "DEADLOCK"),
        new Statement(MssqlQueryStoreCollector.class, "STORE_STATE_SQL", "QSTORE_STATE"),
        new Statement(MssqlQueryStoreCollector.class, "QUERY_STATS_SQL", "QSTORE_STATS"),
        new Statement(MssqlPlanOperatorCollector.class, "METADATA_SQL", "PLAN_METADATA"),
        new Statement(MssqlPlanOperatorCollector.class, "OPERATOR_SQL", "PLAN_OPERATORS"),
        new Statement(MssqlQueryTextCollector.class, "QUERY_TEXT_SQL", "QUERY_TEXT")
    );

    private static String query(Class<?> clazz, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    void allShippedInternalQueriesHaveVersionedMarkerWithoutUsingIds() throws Exception {
        for (var entry : INTERNAL_SQL) {
            String sql = query(entry.type(), entry.field());
            assertThat(sql)
                .as(entry.type().getSimpleName() + "." + entry.field())
                .contains("/* DBPULSE_INTERNAL_" + entry.label() + "_V1 */");
            assertThat(sql).doesNotContain("WHERE query_id = 252 AND plan_id = 197");
        }
    }

    @Test
    void sqlTextLookupHasAnIndependentFallbackForSqlServerCommentStripping() throws Exception {
        String collector = query(MssqlQueryTextCollector.class, "QUERY_TEXT_SQL");
        String classifier = query(MssqlQueryStoreCollector.class, "QUERY_STATS_SQL");
        assertThat(collector).contains("SUBSTRING(qt.query_sql_text", "DATALENGTH(qt.query_sql_text)",
            "AS captured_text", "AS original_bytes", "sys.query_store_query_text");
        // DBPulse must still recognize historical SQL captured *before* markers
        // were deployed. The client JDBC parameter syntax can differ by server.
        assertThat(classifier).contains(
            "CHARINDEX(N'DBPULSE_INTERNAL_', qt.query_sql_text)",
            "qt.query_sql_text LIKE N'%sys.query_store_query_text%'",
            "qt.query_sql_text LIKE N'%SUBSTRING%'",
            "qt.query_sql_text LIKE N'%captured_text%'",
            "qt.query_sql_text LIKE N'%DATALENGTH%'",
            "qt.query_sql_text LIKE N'%original_bytes%'"
        );
        assertThat(classifier).contains("THEN 'DBPULSE_PATTERN'");
        assertThat(classifier).contains("ELSE 'OTHER_OR_UNKNOWN'");
    }
}
