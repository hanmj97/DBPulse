package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.dbpulse.collector.MssqlQueryStoreCollector;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class QueryStoreFailureStageTest {
    @Test void sqlErrorsReturnOnlyStageAndNumericCode() {
        var sql = new SQLException("PRIVATE SQL DRIVER DETAIL", "42S22", 207);
        var result = new MssqlQueryStoreCollector.StageFailure("STATS", sql);
        String code = QueryPerformanceService.safeSqlFailureCode(result);
        assertEquals("QUERY_STORE_STATS_SQL_207", code);
        assertFalse(code.contains("PRIVATE"));
    }

    @Test void connectionFailureHasDistinctCode() {
        var original = new SQLException("contains password", "08001", 0);
        var result = new MssqlQueryStoreCollector.StageFailure("CONNECT", original);
        assertEquals("QUERY_STORE_CONNECT_SQL_0", QueryPerformanceService.safeSqlFailureCode(result));
    }

    @Test void arbitrarySqlStageIsNotExposed() {
        var err = new MssqlQueryStoreCollector.StageFailure(
            "SECRET_DATABASE_NAME", new SQLException("private", null, 12));
        assertEquals("QUERY_STORE_UNCLASSIFIED_SQL_12", QueryPerformanceService.safeSqlFailureCode(err));
    }
}
