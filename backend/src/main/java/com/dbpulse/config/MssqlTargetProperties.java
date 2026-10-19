package com.dbpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isTrustServerCertificate() { return trustServerCertificate; }
    public void setTrustServerCertificate(boolean trustServerCertificate) { this.trustServerCertificate = trustServerCertificate; }
}
