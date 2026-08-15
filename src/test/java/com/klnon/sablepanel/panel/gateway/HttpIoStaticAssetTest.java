package com.klnon.sablepanel.panel.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 静态资源白名单:字体放行且穿越类路径依旧拒绝 */
class HttpIoStaticAssetTest {

    @Test
    void fontAndScriptPathsAllowed() {
        assertTrue(HttpIo.STATIC_ASSET.matcher("/fonts/Monocraft.ttf").matches());
        assertTrue(HttpIo.STATIC_ASSET.matcher("/css/panel.css").matches());
        assertTrue(HttpIo.STATIC_ASSET.matcher("/js/views/bodies.js").matches());
        // R5 落的站点图标当时忘了扩白名单,favicon 404 → 浏览器页签只有默认图标(2026-08-14 用户实测)
        assertTrue(HttpIo.STATIC_ASSET.matcher("/img/favicon-16.png").matches());
        assertTrue(HttpIo.STATIC_ASSET.matcher("/img/favicon-32.png").matches());
    }

    @Test
    void traversalAndForeignTypesRejected() {
        assertFalse(HttpIo.STATIC_ASSET.matcher("/fonts/../sablepanel-server.json").matches());
        assertFalse(HttpIo.STATIC_ASSET.matcher("/fonts/Monocraft-LICENSE.txt").matches());
        assertFalse(HttpIo.STATIC_ASSET.matcher("/js/%2e%2e/config.js").matches());
        assertFalse(HttpIo.STATIC_ASSET.matcher("/vendor/three.min.js").matches());
        assertFalse(HttpIo.STATIC_ASSET.matcher("/img/../sablepanel-server.json").matches());
        assertFalse(HttpIo.STATIC_ASSET.matcher("/img/thumbs/secret.jpg").matches());
    }
}
