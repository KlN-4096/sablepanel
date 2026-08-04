package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class PanelFrameCodec extends MessageToMessageCodec<ByteBuf, PanelFrame> {
    private static final int HEADER_BYTES = 13;
    private static final int MAX_META_BYTES = 64 * 1024;

    @Override
    protected void encode(ChannelHandlerContext context, PanelFrame frame, List<Object> out) {
        byte[] meta = frame.meta().toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = frame.body() == null ? new byte[0] : frame.body();
        ByteBuf buffer = context.alloc().buffer(HEADER_BYTES + meta.length + body.length);
        buffer.writeByte(frame.type());
        buffer.writeLong(frame.requestId());
        buffer.writeInt(meta.length);
        buffer.writeBytes(meta);
        buffer.writeBytes(body);
        out.add(buffer);
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> out) {
        if (input.readableBytes() < HEADER_BYTES) throw new IllegalArgumentException("frame header truncated");
        byte type = input.readByte();
        long requestId = input.readLong();
        int metaLength = input.readInt();
        if (metaLength < 0 || metaLength > MAX_META_BYTES || metaLength > input.readableBytes()) {
            throw new IllegalArgumentException("invalid frame metadata length");
        }
        byte[] metaBytes = new byte[metaLength];
        input.readBytes(metaBytes);
        JsonObject meta = metaLength == 0 ? new JsonObject()
                : JsonParser.parseString(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] body = new byte[input.readableBytes()];
        input.readBytes(body);
        out.add(new PanelFrame(type, requestId, meta, body));
    }
}
