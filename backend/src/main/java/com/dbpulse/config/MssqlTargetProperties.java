package com.dbpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One target per backend process. The legacy .env configuration is preserved,
 * but may be disabled so a verified UI target can be activated ONCE.
 *
 * The immutable published record prevents partial JDBC configuration reads.
 * No switching while pollers are running: restart before selecting a new DB.
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

    public record RuntimeTarget(String id, String name, String host, int port,
        String database, String username, String password, boolean trustServerCertificate) {}

    /**
     * Activates ONLY if legacy .env collection was disabled and no other UI
     * target has ever been activated this process lifetime.
     */
    public synchronized boolean activateOnce(RuntimeTarget newTarget) {
        if (enabled || uiTarget != null || newTarget == null
            || newTarget.id() == null || newTarget.id().isBlank()
            || newTarget.password() == null || newTarget.password().isBlank()) return false;
        uiTarget = newTarget;
        return true;
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
