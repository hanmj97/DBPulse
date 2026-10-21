package com.dbpulse.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import com.dbpulse.config.MssqlTargetProperties;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single-PC local onboarding only; a tested target is NOT an active collector.
 * Login password is used transiently and is never stored, logged or returned.
 */
@Service
public class LocalTargetService {
    private final JdbcTemplate jdbc;
    private final MssqlTargetProperties activeTarget;
    private final boolean enabled;
    private final boolean allowLocalSelfSigned;
    private final LocalTargetPolicy policy;
    private final Semaphore connectionSlots = new Semaphore(1);
    private final AtomicLong lastAttemptMs = new AtomicLong(0);

    @Autowired
    public LocalTargetService(JdbcTemplate jdbc,
        @Value("${dbpulse.local-targets.enabled:false}") boolean enabled,
        @Value("${dbpulse.local-targets.allow-self-signed-lab:false}") boolean allowLocalSelfSigned,
        @Value("${dbpulse.local-targets.allowed-hosts:host.docker.internal,localhost,127.0.0.1}")
            String allowedHosts,
        MssqlTargetProperties activeTarget) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.allowLocalSelfSigned = allowLocalSelfSigned;
        this.policy = new LocalTargetPolicy(allowedHosts);
        this.activeTarget = activeTarget;
    }

    /** Test-only compatibility constructor: no live activation is available. */
    public LocalTargetService(JdbcTemplate jdbc, boolean enabled,
        boolean allowLocalSelfSigned, String allowedHosts) {
        this(jdbc, enabled, allowLocalSelfSigned, allowedHosts, new MssqlTargetProperties());
    }

    public boolean isEnabled() { return enabled; }

    public void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public void validate(ConnectionDetails details) {
        requireEnabled();
        if (!policy.validDisplayName(details.name())
            || !policy.permits(details.host())
            || details.port() < 1 || details.port() > 65535
            || !policy.validDatabaseName(details.databaseName())
            || !policy.validLogin(details.username())
            || details.password() == null || details.password().isBlank()
            || details.password().length() > 512
            || (details.trustLocalCertificate()
                && (!allowLocalSelfSigned || !isLocalDevHost(details.host())))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OR_NOT_ALLOWED_TARGET");
        }
    }

    private static boolean isLocalDevHost(String host) {
        return "127.0.0.1".equalsIgnoreCase(host)
            || "localhost".equalsIgnoreCase(host)
            || "host.docker.internal".equalsIgnoreCase(host);
    }

    public TestResult test(ConnectionDetails details) {
        validate(details);
        // Prevent accidental rapid brute forcing or huge connection fanout.
        if (!connectionSlots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        try {
            long now = System.currentTimeMillis();
            long last = lastAttemptMs.get();
            if (now - last < 500) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            }
            lastAttemptMs.set(now);
            String url = "jdbc:sqlserver://" + details.host() + ":" + details.port()
                + ";databaseName=" + details.databaseName()
                + ";encrypt=true;trustServerCertificate=" + details.trustLocalCertificate()
                + ";loginTimeout=3;socketTimeout=4000"
                + ";applicationIntent=ReadOnly;applicationName=DBPulseLocalConnectionTest";
            Properties authentication = new Properties();
            authentication.setProperty("user", details.username());
            authentication.setProperty("password", details.password());
            // Never print URL, username, JDBC exception text or secret.
            try (Connection conn = DriverManager.getConnection(url, authentication)) {
                conn.setReadOnly(true);
                try (PreparedStatement statement = conn.prepareStatement("SELECT DB_NAME()")) {
                    statement.setQueryTimeout(3);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next() && details.databaseName().equalsIgnoreCase(rows.getString(1))) {
                            return new TestResult("CONNECTED", "READ_ONLY_QUERY_SUCCEEDED");
                        }
                        return new TestResult("FAILED", "DATABASE_MISMATCH");
                    }
                }
            } catch (SQLException error) {
                String sqlState = error.getSQLState();
                String code = sqlState != null && sqlState.startsWith("28")
                    ? "AUTHENTICATION_FAILED"
                    : sqlState != null && sqlState.startsWith("08")
                        ? "CONNECTION_FAILED"
                        : "CONNECTION_OR_TLS_FAILED";
                return new TestResult("FAILED", code);
            }
        } finally {
            connectionSlots.release();
        }
    }

    public RegistrationResult register(ConnectionDetails details) {
        TestResult connection = test(details);
        if (!"CONNECTED".equals(connection.status())) {
            return new RegistrationResult("FAILED", connection.code(), null);
        }
        try {
            String id = java.util.UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO local_target_registry
                  (id, display_name, server_host, server_port, database_name, login_name, status)
                VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')
                """, id, details.name().trim(), details.host().trim(), details.port(),
                details.databaseName().trim(), details.username().trim());
            return new RegistrationResult("REGISTERED_INACTIVE",
                "CREDENTIAL_NOT_STORED_COLLECTOR_NOT_STARTED", id);
        } catch (DataIntegrityViolationException duplicate) {
            return new RegistrationResult("FAILED", "TARGET_ALREADY_REGISTERED", null);
        }
    }

    public List<LocalTargetSummary> list() {
        requireEnabled();
        return jdbc.query("""
            SELECT id, display_name, server_host, server_port, database_name,
                   status, created_at
            FROM local_target_registry ORDER BY created_at DESC LIMIT 100
            """, (rs, row) -> new LocalTargetSummary(
                rs.getString("id"), rs.getString("display_name"),
                rs.getString("server_host"), rs.getInt("server_port"),
                rs.getString("database_name"),
                activeTarget.isUiActivated() && rs.getString("id").equals(activeTarget.getId())
                    ? "ACTIVE_IN_MEMORY" : rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()));
    }

    /**
     * The first UI target replaces disabled .env monitoring for this JVM run.
     * A different target requires backend restart; no live switches or
     * mixed collector histories are allowed. Password remains in RAM only.
     */
    public synchronized StartResult startMonitoring(ConnectionDetails details) {
        requireEnabled();
        if (activeTarget.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "RESTART_BACKEND_TO_CHANGE_TARGET");
        }
        TestResult probe = test(details);
        if (!"CONNECTED".equals(probe.status())) {
            return new StartResult("FAILED", probe.code(), null);
        }

        // Reuse the same ID if a previously tested target was registered.
        // IDs scope the real metrics, incidents and Query Store history,
        // so old local-mssql history is never attributed to the new DB.
        List<String> matching = jdbc.query("""
            SELECT id FROM local_target_registry
            WHERE lower(server_host) = lower(?)
              AND server_port = ?
              AND lower(database_name) = lower(?)
              AND lower(login_name) = lower(?)
            ORDER BY created_at DESC LIMIT 1
            """, (rs, index) -> rs.getString(1),
            details.host(), details.port(), details.databaseName(), details.username());
        String id;
        if (matching.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            try {
                jdbc.update("""
                    INSERT INTO local_target_registry
                    (id, display_name, server_host, server_port, database_name,
                     login_name, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE')
                    """, id, details.name().trim(), details.host().trim(),
                    details.port(), details.databaseName().trim(), details.username().trim());
            } catch (DataIntegrityViolationException concurrent) {
                // If another request registered the target, retrieve the
                // canonical existing ID rather than creating a second stream.
                matching = jdbc.query("""
                    SELECT id FROM local_target_registry
                    WHERE lower(server_host) = lower(?) AND server_port = ?
                      AND lower(database_name) = lower(?)
                      AND lower(login_name) = lower(?)
                    ORDER BY created_at DESC LIMIT 1
                    """, (rs, index) -> rs.getString(1),
                    details.host(), details.port(), details.databaseName(), details.username());
                if (matching.isEmpty()) throw concurrent;
                id = matching.get(0);
            }
        } else id = matching.get(0);

        var config = new MssqlTargetProperties.RuntimeTarget(
            id, details.name().trim(), details.host().trim(), details.port(),
            details.databaseName().trim(), details.username(),
            details.password(), details.trustLocalCertificate());
        if (!activeTarget.activateOnce(config)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "RESTART_BACKEND_TO_CHANGE_TARGET");
        }
        return new StartResult("ACTIVE_IN_MEMORY",
            "MONITORING_STARTED_SESSION_ONLY", id);
    }

    public record StartResult(String status, String code, String id) {}

    public record ConnectionDetails(String name, String host, int port,
        String databaseName, String username, String password,
        boolean trustLocalCertificate) {}
    public record TestResult(String status, String code) {}
    public record RegistrationResult(String status, String code, String id) {}
    public record LocalTargetSummary(String id, String name, String host,
        int port, String databaseName, String status, Instant createdAt) {}
}
