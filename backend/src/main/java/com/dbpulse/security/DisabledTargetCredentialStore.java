package com.dbpulse.security;

import java.util.Optional;

/** Docker/Linux fallback. Never writes plaintext credentials. */
public final class DisabledTargetCredentialStore implements TargetCredentialStore {
    @Override public boolean isAvailable() { return false; }
    @Override public void save(String id, String secret) {
        throw new IllegalStateException("WINDOWS_CREDENTIAL_STORE_UNAVAILABLE");
    }
    @Override public Optional<String> load(String id) { return Optional.empty(); }
    @Override public boolean has(String id) { return false; }
    @Override public void remove(String id) { /* no persisted credentials */ }
}
