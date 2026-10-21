package com.dbpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * One target per backend process. The legacy .env configuration is preserved,
 * but may be disabled so a verified UI target can be activated and later safely disconnected.
 *
 * The immutable published record prevents partial JDBC configuration reads.
 * Pollers and demand-only JDBC actions must hold the shared read lease.
 * Disconnect acquires the exclusive write lease before clearing credentials.
 * The SQL password is held in process memory only; it is never persisted.
 */
@ConfigurationProperties(prefix = "dbpulse.mssql")
public class MssqlTargetProperties {
    private boolean enabled;
    private String id = "local-mssql";
    private String name = "Local SQL Server";
    private String host = "localhost";
    private int port = 1433;
    private String database = "DBPulseLab";
    private String username = "";
    private String password = "";
    private boolean trustServerCertificate;

    private volatile RuntimeTarget uiTarget;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final CopyOnWriteArrayList<Runnable> resetCallbacks = new CopyOnWriteArrayList<>();

    public void onTargetChanged(Runnable callback) { resetCallbacks.add(callback); }

    public void withReadLease(Runnable work) {
        lifecycle.readLock().lock();
        try { work.run(); } finally { lifecycle.readLock().unlock(); }
    }

    public <T> T withReadLease(Supplier<T> work) {
        lifecycle.readLock().lock();
        try { return work.get(); } finally { lifecycle.readLock().unlock(); }
    }

    private void resetAllState() {
        for (Runnable reset : resetCallbacks) reset.run();
    }

    /** Stop the currently UI-selected target; legacy .env targets cannot be stopped from the browser. */
    public boolean disconnectUiTarget() {
        lifecycle.writeLock().lock();
        try {
            if (enabled || uiTarget == null) return false;
            uiTarget = null;
            resetAllState();
            return true;
        } finally { lifecycle.writeLock().unlock(); }
    }

    public record RuntimeTarget(String id, String name, String host, int port,
        String database, String username, String password, boolean trustServerCertificate) {}

    /**
     * A monitored database can only be changed after an explicit disconnect.
     * Activation and state reset are exclusive with collector read leases.
     */
    public boolean activateOnce(RuntimeTarget newTarget) {
        lifecycle.writeLock().lock();
        try {
            if (enabled || uiTarget != null || newTarget == null
                || newTarget.id() == null || newTarget.id().isBlank()
                || newTarget.password() == null || newTarget.password().isBlank()) return false;
            uiTarget = newTarget;
            resetAllState();
            return true;
        } finally { lifecycle.writeLock().unlock(); }
    }

    public boolean isUiActivated() { return uiTarget != null; }
    public boolean isLegacyConfigured() { return enabled; }
    public boolean isEnabled() { return enabled || uiTarget != null; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getId() { var c = uiTarget; return c == null ? id : c.id(); }
    public void setId(String id) { this.id = id; }
    public String getName() { var c = uiTarget; return c == null ? name : c.name(); }
    public void setName(String name) { this.name = name; }
    public String getHost() { var c = uiTarget; return c == null ? host : c.host(); }
    public void setHost(String host) { this.host = host; }
    public int getPort() { var c = uiTarget; return c == null ? port : c.port(); }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { var c = uiTarget; return c == null ? database : c.database(); }
    public void setDatabase(String database) { this.database = database; }
    public String getUsername() { var c = uiTarget; return c == null ? username : c.username(); }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { var c = uiTarget; return c == null ? password : c.password(); }
    public void setPassword(String password) { this.password = password; }
    public boolean isTrustServerCertificate() {
        var c = uiTarget; return c == null ? trustServerCertificate : c.trustServerCertificate();
    }
    public void setTrustServerCertificate(boolean trustServerCertificate) {
        this.trustServerCertificate = trustServerCertificate;
    }
}
