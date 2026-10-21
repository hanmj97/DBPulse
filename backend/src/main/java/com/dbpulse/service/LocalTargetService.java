package com.dbpulse.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import java.time.Instant;
import java.util.List;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.ImportedTimestampReader;
import com.dbpulse.persistence.MetadataTimestampValue;
import com.dbpulse.security.TargetCredentialStore;
import com.dbpulse.security.DisabledTargetCredentialStore;
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
    private volatile Boolean sqliteDialect;
    private boolean sqlite() {
        Boolean value = sqliteDialect;
        if (value == null) {
            value = MetadataTimestampValue.isSqlite(jdbc);
            sqliteDialect = value;
        }
        return value;
    }

    private final MssqlTargetProperties activeTarget;
    private final TargetCredentialStore credentialStore;
    private final boolean enabled;
    private final boolean allowLocalSelfSigned;
    private final LocalTargetPolicy policy;
    private final Semaphore connectionSlots = new Semaphore(1);
    // Failed attempts are rate limited; successful test -> start is allowed immediately.
    private final AtomicLong lastFailedAttemptMs = new AtomicLong(0);

    @Autowired
    public LocalTargetService(JdbcTemplate jdbc,
        @Value("${dbpulse.local-targets.enabled:false}") boolean enabled,
        @Value("${dbpulse.local-targets.allow-self-signed-lab:false}") boolean allowLocalSelfSigned,
        @Value("${dbpulse.local-targets.allowed-hosts:host.docker.internal,localhost,127.0.0.1}")
            String allowedHosts,
        MssqlTargetProperties activeTarget, TargetCredentialStore credentialStore) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.allowLocalSelfSigned = allowLocalSelfSigned;
        this.policy = new LocalTargetPolicy(allowedHosts);
        this.activeTarget = activeTarget;
        this.credentialStore = credentialStore;
    }

    /** Test compatibility: no secret persistence unless explicitly injected. */
    public LocalTargetService(JdbcTemplate jdbc, boolean enabled,
        boolean allowLocalSelfSigned, String allowedHosts,
        MssqlTargetProperties activeTarget) {
        this(jdbc, enabled, allowLocalSelfSigned, allowedHosts,
            activeTarget, new DisabledTargetCredentialStore());
    }

    public LocalTargetService(JdbcTemplate jdbc, boolean enabled,
        boolean allowLocalSelfSigned, String allowedHosts) {
        this(jdbc, enabled, allowLocalSelfSigned, allowedHosts,
            new MssqlTargetProperties(), new DisabledTargetCredentialStore());
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
            long lastFailure = lastFailedAttemptMs.get();
            if (now - lastFailure < 1500) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            }
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
                        lastFailedAttemptMs.set(System.currentTimeMillis());
                        return new TestResult("FAILED", "DATABASE_MISMATCH");
                    }
                }
            } catch (SQLException error) {
                lastFailedAttemptMs.set(System.currentTimeMillis());
                // Only stable, sanitized error codes go to the browser.
                // Never serialize the SQL exception, connection string or credentials.
                return new TestResult("FAILED", classifyConnectionFailure(error));
            }
        } finally {
            connectionSlots.release();
        }
    }

    static String classifyConnectionFailure(SQLException error) {
        String state = error.getSQLState();
        if ((state != null && state.startsWith("28"))
            || error.getErrorCode() == 18456 || error.getErrorCode() == 18452) {
            return "AUTHENTICATION_FAILED";
        }
        if (error.getErrorCode() == 4060 || error.getErrorCode() == 916) {
            return "DATABASE_ACCESS_FAILED";
        }
        if (error instanceof SQLTimeoutException
            || "HYT00".equals(state) || "HYT01".equals(state)) {
            return "CONNECTION_TIMEOUT";
        }
        // A TLS handshake error is not always a certificate trust failure.
        // Only return the more specific untrusted-certificate code if a
        // certificate exception was actually observed in the cause chain.
        Throwable cause = error;
        for (int level = 0; cause != null && level < 12; level++, cause = cause.getCause()) {
            if (cause instanceof CertificateException) return "TLS_CERTIFICATE_UNTRUSTED";
        }
        cause = error;
        for (int level = 0; cause != null && level < 12; level++, cause = cause.getCause()) {
            if (cause instanceof SSLException) return "TLS_HANDSHAKE_FAILED";
            if (cause instanceof SocketTimeoutException) return "CONNECTION_TIMEOUT";
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException) return "CONNECTION_FAILED";
        }
        // SQLSTATE 08 includes *both* TCP failures and TLS negotiation failures.
        return "CONNECTION_OR_TLS_FAILED";
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
                  (id, display_name, server_host, server_port, database_name, login_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE', ?)
                """, id, details.name().trim(), details.host().trim(), details.port(),
                details.databaseName().trim(), details.username().trim(),
                 MetadataTimestampValue.value(sqlite(), Instant.now()));
            return new RegistrationResult("REGISTERED_INACTIVE",
                "CREDENTIAL_NOT_STORED_COLLECTOR_NOT_STARTED", id);
        } catch (DataIntegrityViolationException duplicate) {
            return new RegistrationResult("FAILED", "TARGET_ALREADY_REGISTERED", null);
        }
    }

    public List<LocalTargetSummary> list() {
        requireEnabled();
        return jdbc.query("""
            SELECT id, display_name, server_host, server_port, database_name, login_name,
                   status, created_at
            FROM local_target_registry ORDER BY created_at DESC LIMIT 100
            """, (rs, row) -> new LocalTargetSummary(
                rs.getString("id"), rs.getString("display_name"),
                rs.getString("server_host"), rs.getInt("server_port"),
                rs.getString("database_name"), rs.getString("login_name"),
                credentialStore.has(rs.getString("id")),
                activeTarget.isUiActivated() && rs.getString("id").equals(activeTarget.getId())
                    ? "ACTIVE_IN_MEMORY" : rs.getString("status"),
                ImportedTimestampReader.instant(rs, "created_at")));
    }

    /**
     * Starts a target only while no other monitoring target is active.
     * The user can first disconnect the running target without restarting
     * Java or granting Docker privileges to a browser session.
     */
    public synchronized StartResult startMonitoring(ConnectionDetails details) {
        requireEnabled();
        if (activeTarget.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "STOP_CURRENT_TARGET_FIRST");
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
                    VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED_INACTIVE', ?)
                    """, id, details.name().trim(), details.host().trim(),
                    details.port(), details.databaseName().trim(), details.username().trim(),
                     MetadataTimestampValue.value(sqlite(), Instant.now()));
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

    /**
     * Stops collectors and clears the in-memory SQL credential only after
     * in-flight lease holders finish. Registered metadata/history are retained.
     */
    public synchronized StopResult stopMonitoring(String id) {
        requireEnabled();
        if (activeTarget.isLegacyConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LEGACY_ENV_TARGET_CANNOT_STOP");
        }
        if (!activeTarget.isUiActivated() || !activeTarget.getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NO_MATCHING_ACTIVE_TARGET");
        }
        if (!activeTarget.disconnectUiTarget()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STOP_FAILED");
        }
        return new StopResult("DISCONNECTED", "MONITORING_STOPPED", id);
    }

    /**
     * Rename the local display label only; identity/host/DB/login and all
     * historic target_ids remain unchanged. Active targets must be stopped.
     */
    public synchronized RegistryResult renameRegistered(String id, String name) {
        requireEnabled();
        requireManageable(id);
        if (!policy.validDisplayName(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME");
        }
        int updated = jdbc.update(
            "UPDATE local_target_registry SET display_name = ? WHERE id = ?",
            name.trim(), id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "REGISTERED_TARGET_NOT_FOUND");
        return new RegistryResult("RENAMED", "TARGET_DISPLAY_NAME_UPDATED", id);
    }

    /**
     * Unregister only: old metric/incident/Query Store history is deliberately
     * retained under the original target UUID. No cascade or SQL Server DDL.
     */
    public synchronized RegistryResult unregister(String id) {
        requireEnabled();
        requireManageable(id);
        // Forget persisted DPAPI blob before dropping its metadata reference.
        // An existing active target is already forbidden by requireManageable().
        forgetIfPresent(id);
        int removed = jdbc.update("DELETE FROM local_target_registry WHERE id = ?", id);
        if (removed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "REGISTERED_TARGET_NOT_FOUND");
        return new RegistryResult("UNREGISTERED", "TARGET_UNREGISTERED_HISTORY_PRESERVED", id);
    }

    private void requireManageable(String id) {
        // Only UUIDs issued by registration; never allow the legacy target ID.
        if (id == null || !id.matches(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_ID");
        }
        if (activeTarget.isUiActivated() && id.equalsIgnoreCase(activeTarget.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "DISCONNECT_TARGET_BEFORE_MANAGING");
        }
    }

    public record RenameRequest(String name) {}
    public record RegistryResult(String status, String code, String id) {}

    public CredentialCapability credentialCapability() {
        requireEnabled();
        return new CredentialCapability(credentialStore.isAvailable(),
            credentialStore.isAvailable() ? "WINDOWS_DPAPI_USER" : "UNAVAILABLE_IN_DOCKER");
    }

    public synchronized CredentialResult rememberActiveCredential(String id) {
        requireEnabled();
        if (!credentialStore.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "WINDOWS_DPAPI_NOT_AVAILABLE");
        }
        if (!activeTarget.isUiActivated() || !activeTarget.getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MONITORING_TARGET_NOT_ACTIVE");
        }
        try {
            credentialStore.save(id, activeTarget.getPassword());
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CREDENTIAL_SAVE_FAILED");
        }
        return new CredentialResult("SAVED", "ENCRYPTED_FOR_CURRENT_WINDOWS_USER", id);
    }

    public synchronized CredentialResult forgetCredential(String id) {
        requireEnabled();
        if (!credentialStore.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "WINDOWS_DPAPI_NOT_AVAILABLE");
        }
        requireUuid(id);
        forgetIfPresent(id);
        return new CredentialResult("FORGOTTEN", "SAVED_CREDENTIAL_REMOVED", id);
    }

    private void forgetIfPresent(String id) {
        if (!credentialStore.isAvailable()) return;
        try {
            credentialStore.remove(id);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CREDENTIAL_DELETE_FAILED");
        }
    }

    public synchronized StartResult startWithSavedCredential(String id,
        boolean trustLocalCertificate) {
        requireEnabled();
        if (activeTarget.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STOP_CURRENT_TARGET_FIRST");
        }
        if (!credentialStore.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "WINDOWS_DPAPI_NOT_AVAILABLE");
        }
        requireUuid(id);
        var registered = jdbc.query("""
            SELECT display_name, server_host, server_port, database_name, login_name
            FROM local_target_registry WHERE id = ?
            """, (rs, row) -> new StoredConnection(
                rs.getString("display_name"), rs.getString("server_host"),
                rs.getInt("server_port"), rs.getString("database_name"),
                rs.getString("login_name")), id);
        if (registered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REGISTERED_TARGET_NOT_FOUND");
        }
        String password;
        try {
            password = credentialStore.load(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "SAVED_CREDENTIAL_MISSING"));
        } catch (ResponseStatusException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SAVED_CREDENTIAL_UNAVAILABLE");
        }
        var item = registered.get(0);
        // Same allow-list/TLS/read-only probe as password-entry start. Never
        // bypass validation merely because an encrypted password exists.
        return startMonitoring(new ConnectionDetails(
            item.name(), item.host(), item.port(), item.databaseName(),
            item.username(), password, trustLocalCertificate));
    }

    private record StoredConnection(String name, String host, int port,
        String databaseName, String username) {}
    private void requireUuid(String id) {
        if (id == null || !id.matches(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_ID");
        }
    }

    public record SavedStartRequest(boolean trustLocalCertificate) {}
    public record CredentialCapability(boolean available, String provider) {}
    public record CredentialResult(String status, String code, String id) {}
    public record StopRequest(String id) {}
    public record StopResult(String status, String code, String id) {}
    public record StartResult(String status, String code, String id) {}

    public record ConnectionDetails(String name, String host, int port,
        String databaseName, String username, String password,
        boolean trustLocalCertificate) {}
    public record TestResult(String status, String code) {}
    public record RegistrationResult(String status, String code, String id) {}
    public record LocalTargetSummary(String id, String name, String host,
        int port, String databaseName, String username, boolean savedCredential,
        String status, Instant createdAt) {}
}
