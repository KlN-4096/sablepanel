package com.klnon.sablepanel.panel.transport;

import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelTransportTest {
    @TempDir
    Path temp;

    @Test
    void endpointAcceptsHostPortAndRejectsUrls() {
        assertEquals(new PanelEndpoint("example.com", 25581), PanelEndpoint.parse("example.com", 25581));
        assertEquals(new PanelEndpoint("example.com", 24444), PanelEndpoint.parse("example.com:24444", 25581));
        assertEquals(new PanelEndpoint("::1", 25581), PanelEndpoint.parse("[::1]:25581", 25580));
        assertThrows(IllegalArgumentException.class, () -> PanelEndpoint.parse("https://example.com:25581", 25581));
    }

    @Test
    void requestRoundTripPreservesFields() {
        PanelRequest source = new PanelRequest("POST", "/api/ops/pause", Map.of("a", "b"),
                "{\"paused\":true}".getBytes(), "token", "server-b");
        PanelRequest decoded = PanelWire.request(PanelWire.request(7, source));
        assertEquals(source.method(), decoded.method());
        assertEquals(source.path(), decoded.path());
        assertEquals(source.query(), decoded.query());
        assertEquals(source.token(), decoded.token());
        assertEquals(source.targetServer(), decoded.targetServer());
        assertArrayEquals(source.body(), decoded.body());
        PanelFrame oversized = new PanelFrame(PanelFrame.REQUEST, 8, new com.google.gson.JsonObject(),
                new byte[PanelWire.MAX_REQUEST_BODY + 1]);
        assertThrows(IllegalArgumentException.class, () -> PanelWire.request(oversized));
    }

    @Test
    void largeJsonResponseUsesGzipAndRoundTrips() throws Exception {
        byte[] body = ("{\"value\":\"" + "x".repeat(20_000) + "\"}").getBytes();
        PanelFrame frame = PanelWire.response(3, new PanelResponse(200, "application/json", body, true));
        assertTrue(frame.meta().get("gzip").getAsBoolean());
        PanelResponse decoded = PanelWire.response(frame);
        assertEquals(200, decoded.status());
        assertArrayEquals(body, decoded.body());
    }

    @Test
    void tlsIdentityPersistsAcrossReloads() throws Exception {
        TlsIdentity first = TlsIdentity.loadOrCreate(temp, "test-server");
        TlsIdentity second = TlsIdentity.loadOrCreate(temp, "test-server");
        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(Files.isRegularFile(temp.resolve("sablepanel/tls/server.p12")));
        assertTrue(Files.isRegularFile(temp.resolve("sablepanel/tls/server.pass")));
    }

    @Test
    void tlsClientAndServerExchangeOneRequest() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("loopback"), "loopback");
        PanelTcpServer server = new PanelTcpServer("self", request ->
                PanelResponse.json(200, "{\"path\":\"" + request.path() + "\"}", false), () -> "token");
        PanelTcpClient client = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            CertificatePinException firstConnect = assertThrows(CertificatePinException.class,
                    () -> PanelTcpClient.connectManager(endpoint, ""));
            assertEquals(identity.fingerprint(), firstConnect.fingerprint());
            assertTrue(!firstConnect.changed());
            CertificatePinException changed = assertThrows(CertificatePinException.class,
                    () -> PanelTcpClient.connectManager(endpoint, "00:".repeat(31) + "00"));
            assertTrue(changed.changed());
            client = PanelTcpClient.connectManager(endpoint, identity.fingerprint());
            PanelResponse response = client.request(new PanelRequest("GET", "/api/test", Map.of(),
                    new byte[0], "token", "")).get(5, TimeUnit.SECONDS);
            assertEquals(200, response.status());
            assertTrue(new String(response.body()).contains("/api/test"));
        } finally {
            if (client != null) client.close();
            server.close();
        }
    }

    @Test
    void peerRegistrationCompletesBeforeUse() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("peer-auth"), "host");
        PanelTcpServer server = new PanelTcpServer("host", request ->
                PanelResponse.json(200, "{}", false), () -> "correct-token");
        AtomicReference<String> adoptedToken = new AtomicReference<>();
        PanelTcpClient peer = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            peer = PanelTcpClient.connectPeer(endpoint, "peer",
                    request -> PanelResponse.json(200, "{\"peer\":true}", false),
                    token -> {
                        adoptedToken.set(token);
                        return PanelResponse.json(200, "{}", false);
                    });
            PanelTcpClient connectedPeer = peer;
            assertTrue(server.peerIds().contains("peer"),
                    () -> "registered=" + server.peerIds() + ", active=" + connectedPeer.isActive());
            assertEquals("correct-token", adoptedToken.get());
            assertEquals(200, server.updatePeerToken("peer", "next-token").get(5, TimeUnit.SECONDS).status());
            assertEquals("next-token", adoptedToken.get());
            PanelResponse response = server.requestPeer("peer", new PanelRequest("GET", "/api/test",
                    Map.of(), new byte[0], "correct-token", "")).get(5, TimeUnit.SECONDS);
            assertEquals(200, response.status());
            assertTrue(new String(response.body()).contains("peer"));
        } finally {
            if (peer != null) peer.close();
            server.close();
        }
    }
}
