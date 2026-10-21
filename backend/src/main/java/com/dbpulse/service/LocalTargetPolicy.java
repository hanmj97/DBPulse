package com.dbpulse.service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local-only registration scope. The user's explicit backend allow-list
 * decides which MSSQL servers may be contacted (not arbitrary browser input).
 */
public final class LocalTargetPolicy {
    private final Set<String> hosts;

    public LocalTargetPolicy(String configuredHosts) {
        this.hosts = Arrays.stream(configuredHosts.split(","))
            .map(String::trim).map(String::toLowerCase)
            .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean permits(String host) {
        return host != null && hosts.contains(host.toLowerCase())
            && host.matches("[A-Za-z0-9.-]{1,253}")
            && !host.contains("..") && !host.startsWith(".") && !host.endsWith(".");
    }

    public boolean validDatabaseName(String database) {
        return database != null && database.length() <= 128
            && database.matches("[\\p{L}\\p{N}_ .-]+")
            && !database.isBlank();
    }

    public boolean validLogin(String login) {
        return login != null && login.length() <= 128
            && !login.isBlank() && login.chars().noneMatch(Character::isISOControl);
    }

    public boolean validDisplayName(String name) {
        return name != null && name.length() <= 100 && !name.isBlank()
            && name.chars().noneMatch(Character::isISOControl);
    }
}
