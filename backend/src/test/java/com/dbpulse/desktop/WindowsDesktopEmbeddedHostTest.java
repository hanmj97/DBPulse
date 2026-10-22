package com.dbpulse.desktop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WindowsDesktopEmbeddedHostTest {
    @Test void onlyExactOptInEnablesEmbeddedHost() {
        assertTrue(WindowsDesktopBoot.isEmbeddedHostSignal("1"));
        assertFalse(WindowsDesktopBoot.isEmbeddedHostSignal("true"));
        assertFalse(WindowsDesktopBoot.isEmbeddedHostSignal("0"));
        assertFalse(WindowsDesktopBoot.isEmbeddedHostSignal(null));
    }
}
