package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.service.PlanOperatorSummary;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Opt-in, single query-plan structural evidence. XML parsing happens inside MSSQL.
 * No query plan XML, SQL text, object names, index names, or predicates cross JDBC.
 */
@Component
public class MssqlPlanOperatorCollector {
    private static final long MAX_PLAN_BYTES = 1_000_000L; // NVARCHAR DATALENGTH, bytes
    private static final int MAX_OPERATOR_NODES = 100;

    private static final String METADATA_SQL = """
        SELECT TOP (1) is_parallel_plan, is_forced_plan,
               DATALENGTH(query_plan) AS plan_bytes
        FROM sys.query_store_plan
        WHERE query_id = ? AND plan_id = ?
        """;

    // TOP 101 detects truncation; never fetch XML or arbitrary attributes over JDBC.
    private static final String OPERATOR_SQL = """
        WITH XMLNAMESPACES (DEFAULT 'http://schemas.microsoft.com/sqlserver/2004/07/showplan')
        SELECT TOP (101)
            op.node.value('(@PhysicalOp)[1]', 'nvarchar(100)') AS physical_op
        FROM sys.query_store_plan p
        CROSS APPLY (SELECT TRY_CONVERT(xml, p.query_plan) AS plan_xml) parsed
        CROSS APPLY parsed.plan_xml.nodes('//RelOp') op(node)
        WHERE p.query_id = ? AND p.plan_id = ?
        """;

    private final MssqlTargetProperties target;

    public MssqlPlanOperatorCollector(MssqlTargetProperties target) {
        this.target = target;
    }

    public PlanReport examine(long queryId, long planId) throws SQLException {
        if (queryId <= 0 || planId <= 0) throw new IllegalArgumentException("INVALID_QUERY_PLAN_ID");
        if (!target.isEnabled()) throw new IllegalStateException("MSSQL_NOT_CONFIGURED");
        if (target.getUsername() == null || target.getUsername().isBlank()
            || target.getPassword() == null || target.getPassword().isBlank()) {
            throw new IllegalStateException("MSSQL_CREDENTIALS_NOT_CONFIGURED");
        }
        if (target.getPort() < 1 || target.getPort() > 65535) {
            throw new IllegalStateException("INVALID_MSSQL_PORT");
        }
        String url = "jdbc:sqlserver://" + target.getHost() + ":" + target.getPort()
            + ";databaseName=" + target.getDatabase()
            + ";encrypt=true;trustServerCertificate=" + target.isTrustServerCertificate()
            + ";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulsePlanOperators";
        Properties auth = new Properties();
        auth.setProperty("user", target.getUsername());
        auth.setProperty("password", target.getPassword());
        try (Connection conn = DriverManager.getConnection(url, auth)) {
            conn.setReadOnly(true);
            Boolean parallel = null;
            Boolean forced = null;
            long bytes = 0;
            try (PreparedStatement stmt = conn.prepareStatement(METADATA_SQL)) {
                stmt.setQueryTimeout(4);
                stmt.setLong(1, queryId);
                stmt.setLong(2, planId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) return empty(queryId, planId, "PLAN_NOT_FOUND", null, null, 0);
                    parallel = rs.getBoolean("is_parallel_plan");
                    forced = rs.getBoolean("is_forced_plan");
                    bytes = rs.getLong("plan_bytes");
                }
            }
            if (bytes == 0) return empty(queryId, planId, "PLAN_XML_UNAVAILABLE", parallel, forced, bytes);
            if (bytes > MAX_PLAN_BYTES) return empty(queryId, planId, "PLAN_TOO_LARGE", parallel, forced, bytes);

            List<String> physicalOps = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(OPERATOR_SQL)) {
                stmt.setQueryTimeout(5);
                stmt.setLong(1, queryId);
                stmt.setLong(2, planId);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) physicalOps.add(rs.getString("physical_op"));
                }
            }
            if (physicalOps.isEmpty()) {
                return empty(queryId, planId, "NO_OPERATORS_OBSERVED", parallel, forced, bytes);
            }
            var summary = PlanOperatorSummary.summarize(physicalOps, MAX_OPERATOR_NODES);
            return new PlanReport(queryId, planId,
                summary.truncated() ? "OPERATOR_LIMIT_REACHED" : "AVAILABLE",
                Instant.now(), parallel, forced, bytes,
                summary.sampledNodes(), summary.operators(),
                "Query Store에 보존된 추정 실행계획의 구조적 연산입니다. 실제 실행 횟수·Actual Rows·병목의 확정 원인이 아닙니다. SQL/XML/개체명은 전송하지 않습니다.");
        }
    }

    private static PlanReport empty(long queryId, long planId, String status,
                                    Boolean parallel, Boolean forced, long bytes) {
        return new PlanReport(queryId, planId, status, Instant.now(), parallel,
            forced, bytes, 0, List.of(),
            "실행계획 연산을 확인할 수 없습니다. 누락된 연산을 실제로 없었다고 판단하지 마세요.");
    }

    public record PlanReport(long queryId, long planId, String status,
                             Instant collectedAt, Boolean parallelPlan, Boolean forcedPlan,
                             long planBytes, int sampledOperators,
                             List<PlanOperatorSummary.OperatorCount> operators,
                             String interpretation) {}
}
