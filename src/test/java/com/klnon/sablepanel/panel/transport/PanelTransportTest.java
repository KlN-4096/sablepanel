package com.klnon.sablepanel.panel.transport;

import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        // 应用层的 body 超限断言已随 wire 侧冗余校验退役:上限唯一执法点=PanelFrameDecoder,见下一条用例
    }

    /**
     * meta 是唯一保留出站检查的那一项。meta 装的是 path/query/token,全部来自公网 HTTP 侧
     * (网关 proxyApi 只查 token 非空就转发),一条 70KB 的 URL 就能造出超限帧。
     * 出站必须在本端失败这一次写(调用方兑成 500),绝不能发出去让对端 decoder 拒帧关连接——
     * 那条连接是网关↔HOST 的回环 TLS 链,断一次全部在途请求陪葬。
     */
    @Test
    void oversizedMetaFailsTheWriteInsteadOfKillingTheConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(new PanelFrameCodec());
        try {
            com.google.gson.JsonObject meta = new com.google.gson.JsonObject();
            meta.addProperty("path", "/api/" + "a".repeat(PanelWire.MAX_META_BYTES));
            // 出站异常被 Netty 包成 EncoderException,根因必须是 TooLongFrame(写失败,非断连)
            Throwable thrown = assertThrows(Throwable.class,
                    () -> channel.writeOutbound(new PanelFrame(PanelFrame.REQUEST, 12, meta, new byte[0])));
            Throwable root = thrown;
            while (root.getCause() != null) root = root.getCause();
            assertInstanceOf(TooLongFrameException.class, root, "根因要是帧超限,实际 " + root);
            assertTrue(channel.isActive(), "写失败不该顺带把连接关掉");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void oversizedRequestIsRejectedFromTheHeaderBeforeBodyArrives() {
        ByteBuf header = Unpooled.buffer(PanelWire.LENGTH_FIELD_BYTES + PanelWire.FRAME_HEADER_BYTES);
        header.writeInt(PanelWire.FRAME_HEADER_BYTES + PanelNet.MAX_REQUEST_BODY + 1);
        header.writeByte(PanelFrame.REQUEST);
        header.writeLong(11);
        header.writeInt(0);
        EmbeddedChannel channel = new EmbeddedChannel(new PanelFrameDecoder(false));
        try {
            assertThrows(TooLongFrameException.class, () -> channel.writeInbound(header));
        } finally {
            if (channel.pipeline().context(PanelFrameDecoder.class) != null) {
                channel.pipeline().remove(PanelFrameDecoder.class);
            }
            channel.finishAndReleaseAll();
            if (header.refCnt() > 0) header.release();
        }
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
    void loopbackLinksAreRecognizedForSinglePassHttpCompression() {
        assertTrue(PanelWire.isLoopback(new InetSocketAddress("127.0.0.1", 25581)));
        assertTrue(PanelWire.isLoopback(new InetSocketAddress("::1", 25581)));
        assertTrue(!PanelWire.isLoopback(new InetSocketAddress("192.0.2.10", 25581)));
    }

    @Test
    void previewRoutesAndResponseHeadersSurviveTheWire() throws Exception {
        PanelResponse source = new PanelResponse(200, "application/vnd.sablepanel.resource-shard",
                new byte[]{1, 2, 3}, false, Map.of("ETag", "\"abc\"", "Cache-Control", "private"));

        PanelResponse decoded = PanelWire.response(PanelWire.response(12, source));

        assertEquals(source.contentType(), decoded.contentType());
        assertEquals(source.headers(), decoded.headers());
        assertArrayEquals(source.body(), decoded.body());
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
                json(200, "{\"path\":\"" + request.path() + "\"}", false), () -> "token");
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
                json(200, "{}", false), () -> "correct-token");
        AtomicReference<String> adoptedToken = new AtomicReference<>();
        PanelTcpClient peer = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            peer = PanelTcpClient.connectPeer(endpoint, "peer",
                    request -> json(200, "{\"peer\":true}", false),
                    token -> {
                        adoptedToken.set(token);
                        return json(200, "{}", false);
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

    @Test
    void registeredPeerCarriesSpm2AndResourceResponses() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("peer-preview"), "host");
        PanelTcpServer server = new PanelTcpServer("host", request -> PanelResponse.error(404, "unused"), () -> "token");
        PanelTcpClient peer = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            peer = PanelTcpClient.connectPeer(new PanelEndpoint("127.0.0.1", server.port()), "peer", request ->
                    new PanelResponse(200, request.path().endsWith("/mesh")
                                    ? "application/vnd.sablepanel.mesh-v2" : "application/json",
                            new byte[]{4, 5, 6}, false, Map.of("ETag", "\"preview\"")),
                    token -> json(200, "{}", false));

            PanelResponse mesh = server.requestPeer("peer", new PanelRequest("GET", "/api/body/id/mesh",
                    Map.of(), new byte[0], "token", "")).get(5, TimeUnit.SECONDS);
            assertEquals("application/vnd.sablepanel.mesh-v2", mesh.contentType());
            assertArrayEquals(new byte[]{4, 5, 6}, mesh.body());
            assertEquals("\"preview\"", mesh.headers().get("ETag"));
        } finally {
            if (peer != null) peer.close();
            server.close();
        }
    }

    @Test
    void authenticatedManagerReceivesHostAndPeerEvents() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("events"), "host");
        AtomicReference<PanelTcpServer> serverRef = new AtomicReference<>();
        PanelTcpServer server = new PanelTcpServer("host", request ->
                json(200, "{}", false), () -> "secret");
        serverRef.set(server);
        server.setPeerEvents((peerId, event) -> serverRef.get().publishEvent(event.fromServer(peerId)));
        CountDownLatch received = new CountDownLatch(2);
        List<PanelEvent> events = new CopyOnWriteArrayList<>();
        PanelTcpClient manager = null;
        PanelTcpClient peer = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            manager = PanelTcpClient.connectManager(endpoint, identity.fingerprint(), event -> {
                events.add(event);
                received.countDown();
            });
            assertEquals(401, manager.subscribeEvents("wrong").get(5, TimeUnit.SECONDS).status());
            assertEquals(200, manager.subscribeEvents("secret").get(5, TimeUnit.SECONDS).status());

            server.publishEvent(new PanelEvent("host", 4));
            peer = PanelTcpClient.connectPeer(endpoint, "peer-a", request ->
                    json(200, "{}", false), token -> json(200, "{}", false));
            peer.publishEvent(new PanelEvent("forged", 7));

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(
                    new PanelEvent("host", 4),
                    new PanelEvent("peer-a", 7)), events);
        } finally {
            if (peer != null) peer.close();
            if (manager != null) manager.close();
            server.close();
        }
    }

    @Test
    void registrationAndTokenUpdatesPreserveWireOrder() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("token-order"), "host");
        PanelTcpServer server = new PanelTcpServer("host", request ->
                json(200, "{}", false), () -> "old-token");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> adopted = new CopyOnWriteArrayList<>();
        PanelTcpClient peer = null;
        CompletableFuture<PanelTcpClient> connecting = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            connecting = CompletableFuture.supplyAsync(() -> {
                try {
                    return PanelTcpClient.connectPeer(endpoint, "peer", request ->
                            json(200, "{}", false), token -> {
                        if ("old-token".equals(token)) {
                            firstStarted.countDown();
                            try {
                                releaseFirst.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        adopted.add(token);
                        return json(200, "{}", false);
                    });
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<PanelResponse> update = server.updatePeerToken("peer", "new-token");
            releaseFirst.countDown();
            peer = connecting.get(5, TimeUnit.SECONDS);
            assertEquals(200, update.get(5, TimeUnit.SECONDS).status());
            assertEquals(List.of("old-token", "new-token"), adopted);
        } finally {
            releaseFirst.countDown();
            if (peer != null) peer.close();
            else if (connecting != null) {
                try {
                    connecting.get(5, TimeUnit.SECONDS).close();
                } catch (Exception ignored) {
                }
            }
            server.close();
        }
    }

    @Test
    void handlerExceptionsReturnResponsesInsteadOfTimingOut() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("handler-error"), "handler-error");
        PanelTcpServer server = new PanelTcpServer("self", request -> {
            throw new IllegalStateException("handler exploded");
        }, () -> "token");
        PanelTcpClient client = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            client = PanelTcpClient.connectManager(
                    new PanelEndpoint("127.0.0.1", server.port()), identity.fingerprint());
            PanelResponse response = client.request(new PanelRequest("GET", "/api/error", Map.of(),
                    new byte[0], "token", "")).get(5, TimeUnit.SECONDS);
            assertEquals(500, response.status());
            assertTrue(new String(response.body()).contains("handler exploded"));
        } finally {
            if (client != null) client.close();
            server.close();
        }
    }

    @Test
    void peerHandlerExceptionsReturnResponsesInsteadOfTimingOut() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("peer-handler-error"), "host");
        PanelTcpServer server = new PanelTcpServer("host", request ->
                json(200, "{}", false), () -> "token");
        PanelTcpClient peer = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            peer = PanelTcpClient.connectPeer(new PanelEndpoint("127.0.0.1", server.port()), "peer",
                    request -> {
                        throw new IllegalArgumentException("peer handler exploded");
                    }, token -> json(200, "{}", false));
            PanelResponse response = server.requestPeer("peer", new PanelRequest("GET", "/api/error",
                    Map.of(), new byte[0], "token", "")).get(5, TimeUnit.SECONDS);
            assertEquals(500, response.status());
            assertTrue(new String(response.body()).contains("peer handler exploded"));
        } finally {
            if (peer != null) peer.close();
            server.close();
        }
    }

    @Test
    void oneConnectionCannotQueueUnboundedRequests() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("backpressure"), "backpressure");
        CountDownLatch release = new CountDownLatch(1);
        PanelTcpServer server = new PanelTcpServer("self", request -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return json(200, "{}", false);
        }, () -> "token");
        PanelTcpClient client = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            client = PanelTcpClient.connectManager(
                    new PanelEndpoint("127.0.0.1", server.port()), identity.fingerprint());
            List<CompletableFuture<PanelResponse>> requests = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                requests.add(client.request(new PanelRequest("GET", "/api/slow", Map.of(),
                        new byte[0], "token", "")));
            }
            PanelResponse first = (PanelResponse) CompletableFuture.anyOf(
                    requests.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            assertEquals(503, first.status());
            release.countDown();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertTrue(requests.stream().map(CompletableFuture::join).anyMatch(response -> response.status() == 503));
        } finally {
            release.countDown();
            if (client != null) client.close();
            server.close();
        }
    }

    @Test
    void missingPongClosesTheConnection() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(temp.resolve("pong-timeout"), "pong-timeout");
        PanelTcpServer server = new PanelTcpServer("self", request ->
                json(200, "{}", false), () -> "token", 1, 1, 1);
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            try (SSLSocket socket = (SSLSocket) trustAllTls().getSocketFactory()
                    .createSocket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5_000);
                socket.startHandshake();
                while (socket.getInputStream().read() >= 0) {
                    // Read the PING but intentionally never reply with PONG.
                }
            }
        } finally {
            server.close();
        }
    }

    private static SSLContext trustAllTls() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust, new SecureRandom());
        return context;
    }
    /** 生产侧的字符串 json 重载已删（仅测试引用），测试自备一份 */
    private static PanelResponse json(int status, String body, boolean compressible) {
        return new PanelResponse(status, "application/json", body.getBytes(StandardCharsets.UTF_8), compressible);
    }

}
