package com.dbpulse.desktop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only localhost detection for a second DBPulse.exe launch.
 * This is NOT authentication or a network discovery service. It only decides
 * whether it is safe to reopen the same local dashboard or to fail closed.
 * Never kills a process, asks for a DB password or starts a collector.
 */
public final class DesktopInstanceHandoff {
    private static final int DEFAULT_PORT = 18081;
    private static final int RESPONSE_BYTES_LIMIT = 4096;
    private static final Duration HTTP_TIMEOUT = Duration.ofMillis(1200);
    private static final Pattern SERVICE =
        Pattern.compile("\\\"service\\\"\\s*:\\s*\\\"DBPulse\\\"");
    private static final Pattern UP =
        Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"UP\\\"");
    private static final Pattern PHASE =
        Pattern.compile("\\\"phase\\\"\\s*:\\s*14\\b");

    public enum Result { FREE, DBPULSE_RUNNING, OTHER_SERVICE_ON_PORT }

    private DesktopInstanceHandoff() {}

    public static Result inspect() {
        return inspect(DEFAULT_PORT);
    }

    static Result inspect(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("INVALID_PORT");
        // The TCP probe distinguishes "nothing listening" from a listening
        // service that cannot provide DBPulse's strict health/UI identity.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
        } catch (IOException notListening) {
            return Result.FREE;
        }

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(600))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            String root = "http://127.0.0.1:" + port;
            HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(root + "/api/v1/health"))
                    .timeout(HTTP_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200
                || !health.headers().firstValue("content-type")
                    .orElse("").toLowerCase(Locale.ROOT).contains("application/json")
                || health.body().length() > RESPONSE_BYTES_LIMIT
                || !SERVICE.matcher(health.body()).find()
                || !UP.matcher(health.body()).find()
                || !PHASE.matcher(health.body()).find()) {
                return Result.OTHER_SERVICE_ON_PORT;
            }
            HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(root + "/"))
                    .timeout(HTTP_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (page.statusCode() != 200
                || page.body().length() > 8192
                || !page.headers().firstValue("content-type")
                    .orElse("").toLowerCase(Locale.ROOT).contains("text/html")
                || !page.body().contains("DBPulse")) {
                return Result.OTHER_SERVICE_ON_PORT;
            }
            return Result.DBPULSE_RUNNING;
        } catch (IOException | InterruptedException | RuntimeException inaccessible) {
            if (inaccessible instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.OTHER_SERVICE_ON_PORT;
        }
    }
}
