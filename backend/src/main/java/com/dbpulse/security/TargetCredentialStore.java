package com.dbpulse.security;

import java.util.Optional;

/** Secret-only store. Registry metadata and collector history live elsewhere. */
public interface TargetCredentialStore {
    boolean isAvailable();
    void save(String targetId, String password);
    Optional<String> load(String targetId);
    boolean has(String targetId);
    void remove(String targetId);
}
