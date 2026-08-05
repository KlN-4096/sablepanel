package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;

/**
 * 发送前的最后一道闸:响应真的超过协议上限时,降级成一个能显示的错误,而不是让传输层拒发。
 * <p>
 * 前面每个发送点都记账了,但记账是靠人写对的 —— 连续几轮审计,每轮都能找出一个漏掉的口子
 * (clone_sets 的名称、组名、摘要里没截断的 dims、单成员的调色板……)。这道闸不指望自己
 * 永远不被触发,它的作用是让"下一个还没被发现的口子"从"回收站/物理体列表整个打不开、
 * 刷新一次就再压一次服务端"变成"页面照常打开,顶部一行红字说数据被裁了"。
 * <p>
 * 触发即 error 级日志:它一旦响,就说明上游有个字段没记账,那是要修的 bug,不是正常降级。
 */
final class ResponseGuard {
    /** 与 {@code PanelWire.MAX_FRAME_BYTES} 对齐;留一档余量给帧头和 meta */
    private static final long WIRE_LIMIT_BYTES = 30L << 20;

    private ResponseGuard() {
    }

    /**
     * @param arrays 超限时要清空的大数组字段名(列表本体),清完仍超限就整份丢掉
     * @return 原对象,或降级后的对象
     */
    static JsonObject enforce(String endpoint, JsonObject out, String... arrays) {
        long size = JsonSize.of(out);
        if (size <= WIRE_LIMIT_BYTES) return out;
        SablePanel.LOGGER.error(
                "sablepanel: {} 响应 {} 字节越过协议上限,有字段没有记进预算,已降级发出;这是需要修的 bug",
                endpoint, size);
        for (String field : arrays) {
            if (out.has(field)) out.add(field, new JsonArray());
        }
        out.addProperty("truncated", true);
        out.addProperty("over_limit", true);
        if (JsonSize.of(out) <= WIRE_LIMIT_BYTES) return out;
        // 连清空列表都不够,说明超限的是标量字段:只留一个能渲染的最小壳
        JsonObject minimal = new JsonObject();
        minimal.addProperty("truncated", true);
        minimal.addProperty("over_limit", true);
        for (String field : arrays) minimal.add(field, new JsonArray());
        return minimal;
    }
}
