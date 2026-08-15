package com.klnon.sablepanel.panel.transport;

import com.klnon.sablepanel.panel.api.PanelRequest;
import com.klnon.sablepanel.panel.api.PanelResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服转发的 bulkhead。
 * <p>
 * 回调池全局只有 4 个线程,而 {@code PanelClusterNode.handle} 转发给 PEER 时是在回调线程上
 * 同步等最多 30 秒 —— 4 个慢 PEER 请求就能让完全无关连接的本地请求一起排队,那正是生产上
 * 面板整体 503 的形状。这里用 latch 判定先后关系,不用毫秒阈值。
 */
class PanelForwardBulkheadTest {

    @TempDir
    Path temp;

    private static final int SLOW_REQUESTS = 4;

    @Test
    void slowForwardedRequestsDoNotBlockLocalRequestsFromAnotherConnection() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(this.temp.resolve("bulkhead"), "bulkhead");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch forwardsStarted = new CountDownLatch(SLOW_REQUESTS);
        // 有 target 的请求 = 跨服转发,卡住不放;没有 target 的 = 本机请求,立刻返回
        PanelTcpServer server = new PanelTcpServer("self", request -> {
            if (request.targetServer().isEmpty()) return json(200, "{\"local\":true}", false);
            forwardsStarted.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return json(200, "{\"forwarded\":true}", false);
        }, () -> "token");

        PanelTcpClient slowClient = null;
        PanelTcpClient fastClient = null;
        try {
            server.start("127.0.0.1", 0, identity.serverContext());
            PanelEndpoint endpoint = new PanelEndpoint("127.0.0.1", server.port());
            slowClient = PanelTcpClient.connectManager(endpoint, identity.fingerprint());
            fastClient = PanelTcpClient.connectManager(endpoint, identity.fingerprint());

            List<CompletableFuture<PanelResponse>> slow = new ArrayList<>();
            for (int i = 0; i < SLOW_REQUESTS; i++) {
                slow.add(slowClient.request(new PanelRequest("GET", "/api/bodies", Map.of(),
                        new byte[0], "token", "other-server")));
            }
            assertTrue(forwardsStarted.await(10, TimeUnit.SECONDS),
                    "四个转发请求应当同时占住转发池");

            // latch 还没放开:另一条连接的本机请求必须现在就能完成
            PanelResponse local = fastClient.request(new PanelRequest("GET", "/api/bodies", Map.of(),
                    new byte[0], "token", "")).get(10, TimeUnit.SECONDS);
            assertEquals(200, local.status());
            assertTrue(new String(local.body()).contains("local"));
            for (CompletableFuture<PanelResponse> pending : slow) {
                assertFalse(pending.isDone(), "本机请求先完成时,转发请求应当仍在等待");
            }

            release.countDown();
            CompletableFuture.allOf(slow.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            for (CompletableFuture<PanelResponse> done : slow) assertEquals(200, done.join().status());
        } finally {
            release.countDown();
            if (slowClient != null) slowClient.close();
            if (fastClient != null) fastClient.close();
            server.close();
        }
    }

    @Test
    void slowForwardsDoNotConsumeTheLocalInFlightBudgetOfTheSameConnection() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(this.temp.resolve("same-conn"), "same-conn");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch forwardsStarted = new CountDownLatch(SLOW_REQUESTS);
        PanelTcpServer server = new PanelTcpServer("self", request -> {
            if (request.targetServer().isEmpty()) return json(200, "{\"local\":true}", false);
            forwardsStarted.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
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
            List<CompletableFuture<PanelResponse>> slow = new ArrayList<>();
            for (int i = 0; i < SLOW_REQUESTS; i++) {
                slow.add(client.request(new PanelRequest("GET", "/api/bodies", Map.of(),
                        new byte[0], "token", "other-server")));
            }
            assertTrue(forwardsStarted.await(10, TimeUnit.SECONDS));
            // 浏览器的全部请求都走网关这一条连接:转发占满在途配额时,本机请求也会被顶成 503
            PanelResponse local = client.request(new PanelRequest("GET", "/api/bodies", Map.of(),
                    new byte[0], "token", "")).get(10, TimeUnit.SECONDS);
            assertEquals(200, local.status(), "同一连接上的本机请求不该被转发挤掉");
            release.countDown();
            CompletableFuture.allOf(slow.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            if (client != null) client.close();
            server.close();
        }
    }
    /** 生产侧的字符串 json 重载已删（仅测试引用），测试自备一份 */
    private static PanelResponse json(int status, String body, boolean compressible) {
        return new PanelResponse(status, "application/json", body.getBytes(StandardCharsets.UTF_8), compressible);
    }

}
