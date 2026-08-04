package com.klnon.sablepanel.panel.client;

import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.PanelConfig;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.transport.PanelEvent;
import com.klnon.sablepanel.panel.transport.PanelTcpServer;
import com.klnon.sablepanel.panel.transport.TlsIdentity;
import com.klnon.sablepanel.panel.web.PanelWebGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPanelGatewayTest {
    @TempDir
    Path temp;

    @Test
    void confirmsCertificateAndProxiesOneActiveCluster() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(this.temp, "gateway-test");
        AtomicReference<String> currentToken = new AtomicReference<>("secret");
        PanelTcpServer api = new PanelTcpServer("host", request -> {
            if (!currentToken.get().equals(request.token())) return PanelResponse.error(401, "token 无效");
            if ("/api/large".equals(request.path())) {
                return PanelResponse.json(200, "{\"value\":\"" + "x".repeat(8_000) + "\"}", true);
            }
            return PanelResponse.json(200, "{\"ok\":true}", false);
        }, currentToken::get);
        PanelWebGateway gateway = null;
        try {
            api.start("127.0.0.1", 0, identity.serverContext());
            int webPort = freePort();
            ClientPanelConfig config = ClientPanelConfig.load(this.temp.resolve("client.json"));
            config.webPort = webPort;
            gateway = PanelWebGateway.client(config);
            gateway.start();

            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + webPort;
            String address = "127.0.0.1:" + api.port();
            assertEquals(403, post(http, base + "/gateway/connect", "http://evil.example",
                    "{\"address\":\"" + address + "\"}").statusCode());

            HttpResponse<String> first = post(http, base + "/gateway/connect", base,
                    "{\"address\":\"" + address + "\"}");
            assertEquals(409, first.statusCode());
            String fingerprint = JsonParser.parseString(first.body()).getAsJsonObject()
                    .get("fingerprint").getAsString();
            assertEquals(identity.fingerprint(), fingerprint);
            assertTrue(config.certificatePins.isEmpty());

            String confirm = "{\"address\":\"" + address + "\",\"accept_fingerprint\":\""
                    + fingerprint + "\"}";
            assertEquals(200, post(http, base + "/gateway/connect", base, confirm).statusCode());
            HttpRequest deniedRequest = HttpRequest.newBuilder(URI.create(base + "/api/test"))
                    .header("X-Token", "wrong").GET().build();
            assertEquals(401, http.send(deniedRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest apiRequest = HttpRequest.newBuilder(URI.create(base + "/api/test"))
                    .header("X-Token", "secret").GET().build();
            assertEquals(200, http.send(apiRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest deniedEvents = HttpRequest.newBuilder(URI.create(base + "/api/events"))
                    .header("X-Token", "wrong").GET().build();
            assertEquals(401, http.send(deniedEvents, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest eventRequest = HttpRequest.newBuilder(URI.create(base + "/api/events"))
                    .header("X-Token", "secret").GET().build();
            HttpResponse<InputStream> eventResponse = http.send(eventRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, eventResponse.statusCode());
            try (InputStream input = eventResponse.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                assertTrue(readEvent(reader, "ready").contains("event: ready"));
                CompletableFuture<String> pushed = CompletableFuture.supplyAsync(() -> readEvent(reader, "bodies"));
                api.publishEvent(new PanelEvent("host", PanelEvent.BODIES, 12));
                String event = pushed.get(5, TimeUnit.SECONDS);
                assertTrue(event.contains("\"server\":\"host\""));
                assertTrue(event.contains("\"revision\":12"));
                assertEquals(200, http.send(apiRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

                currentToken.set("next");
                api.revokeEventSubscriptions();
                HttpRequest nextEvents = HttpRequest.newBuilder(URI.create(base + "/api/events"))
                        .header("X-Token", "next").GET().build();
                HttpResponse<InputStream> nextResponse = http.send(nextEvents, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, nextResponse.statusCode());
                try (InputStream nextInput = nextResponse.body();
                     BufferedReader nextReader = new BufferedReader(new InputStreamReader(nextInput, StandardCharsets.UTF_8))) {
                    assertTrue(readEvent(nextReader, "ready").contains("event: ready"));
                    assertNull(awaitClosed(reader));
                    CompletableFuture<String> nextPushed = CompletableFuture.supplyAsync(
                            () -> readEvent(nextReader, "bodies"));
                    api.publishEvent(new PanelEvent("host", PanelEvent.BODIES, 13));
                    assertTrue(nextPushed.get(5, TimeUnit.SECONDS).contains("\"revision\":13"));

                    HttpRequest gzipRequest = HttpRequest.newBuilder(URI.create(base + "/api/large"))
                            .header("X-Token", "next").header("Accept-Encoding", "gzip").GET().build();
                    HttpResponse<byte[]> gzipResponse = http.send(gzipRequest, HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, gzipResponse.statusCode());
                    assertEquals("gzip", gzipResponse.headers().firstValue("Content-Encoding").orElse(""));
                    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipResponse.body()))) {
                        assertTrue(new String(gzip.readAllBytes()).contains("\"value\""));
                    }
                    assertEquals(200, post(http, base + "/gateway/disconnect", base, "{}").statusCode());
                    assertNull(awaitClosed(nextReader));
                }
            }
        } finally {
            if (gateway != null) gateway.close();
            api.close();
        }
    }

    @Test
    void serverGatewayReconnectsAfterLocalApiRestart() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(this.temp.resolve("server-reconnect"), "server-reconnect");
        PanelTcpServer first = new PanelTcpServer("host", request ->
                PanelResponse.json(200, "{\"generation\":1}", false), () -> "secret");
        PanelTcpServer second = null;
        PanelWebGateway gateway = null;
        try {
            first.start("127.0.0.1", 0, identity.serverContext());
            PanelConfig config = new PanelConfig();
            config.webBind = "127.0.0.1";
            config.webPort = freePort();
            config.apiBind = "127.0.0.1";
            config.apiPort = first.port();
            gateway = PanelWebGateway.server(config, identity.fingerprint());
            gateway.start();

            HttpClient http = HttpClient.newHttpClient();
            String apiUrl = "http://127.0.0.1:" + config.webPort + "/api/test";
            assertEquals(200, get(http, apiUrl, "secret").statusCode());

            first.close();
            second = new PanelTcpServer("host", request ->
                    PanelResponse.json(200, "{\"generation\":2}", false), () -> "secret");
            second.start("127.0.0.1", config.apiPort, identity.serverContext());

            HttpResponse<String> recovered = awaitGet(http, apiUrl, "secret", 200, 10_000);
            assertTrue(recovered.body().contains("\"generation\":2"));
        } finally {
            if (gateway != null) gateway.close();
            if (second != null) second.close();
            first.close();
        }
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String origin, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String readEvent(BufferedReader reader, String eventName) {
        try {
            StringBuilder event = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty()) {
                    String value = event.toString();
                    if (value.contains("event: " + eventName)) return value;
                    event.setLength(0);
                } else {
                    event.append(line).append('\n');
                }
            }
            throw new IOException("SSE stream closed before " + eventName);
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private static String awaitClosed(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException closed) {
                return null;
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private static HttpResponse<String> get(HttpClient client, String uri, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).header("X-Token", token).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> awaitGet(HttpClient client, String uri, String token,
                                                  int expectedStatus, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        HttpResponse<String> response = get(client, uri, token);
        while (response.statusCode() != expectedStatus && System.nanoTime() < deadline) {
            Thread.sleep(200);
            response = get(client, uri, token);
        }
        assertEquals(expectedStatus, response.statusCode());
        return response;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
