package com.klnon.sablepanel.panel.gateway;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PanelWebGatewayGzipCacheTest {
    @Test
    void onlyTheLatestBodiesSnapshotIsCompressedOnce() throws Exception {
        PanelWebGateway.LatestGzip cache = new PanelWebGateway.LatestGzip();
        byte[] firstBody = "first".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] first = cache.get("node-a:1", firstBody);

        assertSame(first, cache.get("node-a:1", firstBody));
        assertArrayEquals(firstBody, gunzip(first));

        byte[] secondBody = "second".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] second = cache.get("node-a:2", secondBody);
        assertNotSame(first, second);
        assertArrayEquals(secondBody, gunzip(second));
    }

    private static byte[] gunzip(byte[] value) throws Exception {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value))) {
            return input.readAllBytes();
        }
    }
}
