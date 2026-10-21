package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class LocalTargetConnectionFailureTest {
    @Test void distinguishesCertTrustFromOrdinaryNetworkFailure() {
        assertEquals("TLS_CERTIFICATE_UNTRUSTED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01", 0,
                    new SSLHandshakeException("certificate"))));
        assertEquals("TLS_CERTIFICATE_UNTRUSTED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01", 0,
                    new CertificateException("untrusted certificate"))));
        assertEquals("TLS_HANDSHAKE_FAILED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01", 0,
                    new SSLException("handshake"))));
        assertEquals("CONNECTION_FAILED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01", 0,
                    new ConnectException("connection refused"))));
        assertEquals("CONNECTION_OR_TLS_FAILED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01")));
    }

    @Test void distinguishesTimeoutAndLoginFailures() {
        assertEquals("CONNECTION_TIMEOUT",
            LocalTargetService.classifyConnectionFailure(
                new SQLTimeoutException("private details", "HYT00")));
        assertEquals("CONNECTION_TIMEOUT",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "08S01", 0,
                    new SocketTimeoutException("timed out"))));
        assertEquals("AUTHENTICATION_FAILED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "28000", 18456)));
        assertEquals("DATABASE_ACCESS_FAILED",
            LocalTargetService.classifyConnectionFailure(
                new SQLException("private details", "S0001", 4060)));
    }
}
