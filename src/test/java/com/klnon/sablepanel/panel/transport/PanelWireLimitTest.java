package com.klnon.sablepanel.panel.transport;

import com.klnon.sablepanel.panel.api.PanelResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应字节上限的唯一出口。
 * <p>
 * 前几轮的上限都放在构建点(BodyIndex 的预算、RecycleStore 的单页预算、后来的
 * {@code PanelResponse.capped})。每一处都只算自己那一部分,于是每轮审计都能再找到一条
 * 绕过去的路:守卫跑完之后调用方又追加了字段、集群节点自己应答的 {@code /api/servers}
 * 压根不经过 API 层、异常路径直接构造响应。
 * <p>
 * 真正的必经之路只有 {@link PanelWire#response(long, PanelResponse)} ——
 * {@code PanelTcpServer} 和 {@code PanelTcpClient} 两边所有 {@code sendResponse} 都过它。
 * 本用例判的就是这一点:超限出来的必须是一个小的 500 帧,不是异常(异常等于断连),
 * 也不是缺字段的 200。
 */
class PanelWireLimitTest {

    private static PanelResponse oversized(boolean compressible) {
        byte[] body = new byte[PanelWire.MAX_BODY_BYTES + 1];
        Arrays.fill(body, (byte) 'x');
        return new PanelResponse(200, "application/json", body, compressible);
    }

    private static String bodyText(PanelFrame frame) {
        return new String(frame.body(), StandardCharsets.UTF_8);
    }

    @Test
    void oversizedResponseBecomesASmallErrorFrameNotADroppedConnection() throws Exception {
        PanelFrame frame = PanelWire.response(7, oversized(false));

        assertEquals(PanelFrame.RESPONSE, frame.type());
        assertEquals(7, frame.requestId(), "帧号要保持,否则请求方那边会一直挂到超时");
        assertEquals(500, frame.meta().get("status").getAsInt(), "超限必须是错误,不能是成功");
        assertTrue(frame.body().length < 4096, "错误响应本身必须是小的,实际 " + frame.body().length);
        assertTrue(bodyText(frame).contains("超过服务器内部上限"), "要说清是超限,实际 " + bodyText(frame));
    }

    /**
     * 压缩前判,不是压缩后判。
     * <p>
     * 31 MiB 的同一个字符能压到几十 KB,压缩后的帧长检查根本拦不住;而接收侧的 gunzip
     * 按 {@link PanelWire#MAX_FRAME_BYTES} 封顶,这一帧会改成在对端解压时炸掉 ——
     * 症状从"这台机器报错"变成"另一台机器断连",更难查。
     */
    @Test
    void oversizedCompressibleResponseIsCappedBeforeItIsCompressed() throws Exception {
        PanelFrame frame = PanelWire.response(9, oversized(true));

        assertEquals(500, frame.meta().get("status").getAsInt(),
                "能压缩不等于能发:接收侧解压同样有上限");
        assertTrue(frame.body().length < 4096, "错误响应本身必须是小的,实际 " + frame.body().length);
        assertTrue(frame.meta().has("gzip") && !frame.meta().get("gzip").getAsBoolean(),
                "换成 500 之后就没什么可压的了");
    }

    @Test
    void ordinaryResponseGoesThroughUntouched() throws Exception {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        PanelFrame frame = PanelWire.response(3, new PanelResponse(200, "application/json", body, false));

        assertEquals(200, frame.meta().get("status").getAsInt());
        assertArrayEquals(body, frame.body(), "没超限的响应必须原样发出");
    }
}
