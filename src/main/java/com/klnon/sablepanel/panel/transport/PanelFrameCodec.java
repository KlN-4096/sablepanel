package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.TooLongFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 帧与字节的编解码。入站大小校验只在 {@link PanelFrameDecoder}(缓冲累积前的唯一有防御价值层)。
 * <p>
 * 出站唯一保留 meta 上限一道:meta 里的 path/query/token 来自公网 HTTP 侧且此前无界
 * (网关 proxyApi 只查 token 非空就转发),没有这道闸,一条 70KB URL 就能让对端 decoder
 * 拒帧并关掉网关↔HOST 的回环 TLS 链——那是全部面板流量的载体。这里抛 TooLongFrame 只
 * 失败这一次写(Pending 兜成 500),连接不动。body/帧总长的出站检查确实冗余
 * (响应有 cap()、请求体有 HTTP 侧 1 MiB),不设第二道闸。
 */
final class PanelFrameCodec extends MessageToMessageCodec<ByteBuf, PanelFrame> {
    @Override
    protected void encode(ChannelHandlerContext context, PanelFrame frame, List<Object> out) {
        byte[] meta = frame.meta().toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = frame.body() == null ? new byte[0] : frame.body();
        if (meta.length > PanelWire.MAX_META_BYTES) throw new TooLongFrameException("frame metadata too large");
        long frameBytes = (long) PanelWire.FRAME_HEADER_BYTES + meta.length + body.length;
        ByteBuf buffer = context.alloc().buffer((int) frameBytes);
        buffer.writeByte(frame.type());
        buffer.writeLong(frame.requestId());
        buffer.writeInt(meta.length);
        buffer.writeBytes(meta);
        buffer.writeBytes(body);
        out.add(buffer);
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> out) {
        byte type = input.readByte();
        long requestId = input.readLong();
        int metaLength = input.readInt();
        byte[] metaBytes = new byte[metaLength];
        input.readBytes(metaBytes);
        JsonObject meta = metaLength == 0 ? new JsonObject()
                : JsonParser.parseString(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] body = new byte[input.readableBytes()];
        input.readBytes(body);
        out.add(new PanelFrame(type, requestId, meta, body));
    }
}
