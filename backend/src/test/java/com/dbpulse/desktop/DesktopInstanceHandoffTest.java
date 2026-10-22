package com.dbpulse.desktop;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic servers: no database, DPAPI secret or actual browser required. */
class DesktopInstanceHandoffTest {

    @Test void recognizesAHealthyDbpulseUiAndApiOnSameLoopbackPort() throws Exception {
        HttpServer mock = mockServer(
            "{\"service\":\"DBPulse\",\"status\":\"UP\",\"phase\":14}",
            "<!doctype html><html><head><title>DBPulse</title></head><body>DBPulse</body></html>");
        try {
            assertEquals(DesktopInstanceHandoff.Result.DBPULSE_RUNNING,
                DesktopInstanceHandoff.inspect(mock.getAddress().getPort()));
        } finally { mock.stop(0); }
    }

    @Test void anUnrelatedServiceMustNotBeOpenedAsDbpulse() throws Exception {
        HttpServer mock = mockServer(
            "{\"service\":\"DifferentService\",\"status\":\"UP\",\"phase\":14}",
            "<html><title>Different app</title></html>");
        try {
            assertEquals(DesktopInstanceHandoff.Result.OTHER_SERVICE_ON_PORT,
                DesktopInstanceHandoff.inspect(mock.getAddress().getPort()));
        } finally { mock.stop(0); }
    }

    @Test void aFakeHealthEndpointWithoutDbpulseUiIsNotTrusted() throws Exception {
        HttpServer mock = mockServer(
            "{\"service\":\"DBPulse\",\"status\":\"UP\",\"phase\":14}",
            "<html><title>Impersonated application</title></html>");
        try {
            assertEquals(DesktopInstanceHandoff.Result.OTHER_SERVICE_ON_PORT,
                DesktopInstanceHandoff.inspect(mock.getAddress().getPort()));
        } finally { mock.stop(0); }
    }

    @Test void closedPortIsFree() throws Exception {
        int port;
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = holder.getLocalPort();
        }
        assertEquals(DesktopInstanceHandoff.Result.FREE, DesktopInstanceHandoff.inspect(port));
        assertThrows(IllegalArgumentException.class, () -> DesktopInstanceHandoff.inspect(-1));
    }

    @Test void occupiedPortWithoutAnHttpServiceFailsClosed() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(DesktopInstanceHandoff.Result.OTHER_SERVICE_ON_PORT,
                DesktopInstanceHandoff.inspect(occupied.getLocalPort()));
        }
    }

    private static HttpServer mockServer(String health, String page) throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress("127.0.0.1",0), 0);
        server.createContext("/api/v1/health", exchange -> {
            var bytes = health.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/", exchange -> {
            var bytes = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}
