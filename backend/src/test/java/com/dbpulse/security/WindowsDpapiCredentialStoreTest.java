package com.dbpulse.security;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WindowsDpapiCredentialStoreTest {
    @Test void uuidValidationPreventsTraversalBeforeAnyOsDpapiCall() {
        var store=new WindowsDpapiCredentialStore(Path.of("unimportant-test-directory"));
        assertThrows(IllegalArgumentException.class,()->store.has("../Windows/secret"));
        assertThrows(IllegalArgumentException.class,()->store.load("local-mssql"));
        assertThrows(IllegalArgumentException.class,()->store.remove("../../../"));
        assertThrows(IllegalArgumentException.class,()->store.save("not-a-uuid","foo"));
        assertFalse(store.has("e89fda4a-27e5-4e38-91b7-ddaac822eed3"));
    }

    @Test void disabledFallbackNeverExposesOrPersistsSecrets() {
        var store=new DisabledTargetCredentialStore();
        assertFalse(store.isAvailable());
        assertTrue(store.load("anything").isEmpty());
        assertFalse(store.has("anything"));
        assertThrows(IllegalStateException.class,()->store.save("anything","secret"));
    }
}
