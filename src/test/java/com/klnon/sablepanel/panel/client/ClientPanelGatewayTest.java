package com.klnon.sablepanel.panel.client;

import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.api.PanelResponse;
import com.klnon.sablepanel.panel.transport.PanelTcpServer;
import com.klnon.sablepanel.panel.transport.TlsIdentity;
import com.klnon.sablepanel.panel.web.PanelWebGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPanelGatewayTest {
    @TempDir
    Path temp;

    @Test
    void confirmsCertificateAndProxiesOneActiveCluster() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(this.temp, "gateway-test");
        PanelTcpServer api = new PanelTcpServer("host", request -> "secret".equals(request.token())
                ? PanelResponse.json(200, "{\"ok\":true}", false)
                : PanelResponse.error(401, "token 无效"), () -> "secret");
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
            assertEquals(200, post(http, base + "/gateway/disconnect", base, "{}").statusCode());
        } finally {
            if (gateway != null) gateway.close();
            api.close();
        }
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String origin, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
