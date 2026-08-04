package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.MessageToMessageCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class PanelFrameCodec extends MessageToMessageCodec<ByteBuf, PanelFrame> {
    @Override
    protected void encode(ChannelHandlerContext context, PanelFrame frame, List<Object> out) {
        byte[] meta = frame.meta().toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = frame.body() == null ? new byte[0] : frame.body();
        if (meta.length > PanelWire.MAX_META_BYTES) throw new TooLongFrameException("frame metadata too large");
        if (frame.type() == PanelFrame.REQUEST && body.length > PanelWire.MAX_REQUEST_BODY) {
            throw new TooLongFrameException("request body too large");
        }
        long frameBytes = (long) PanelWire.FRAME_HEADER_BYTES + meta.length + body.length;
        if (PanelWire.LENGTH_FIELD_BYTES + frameBytes > PanelWire.MAX_FRAME_BYTES) {
            throw new TooLongFrameException("frame too large");
        }
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
        if (input.readableBytes() < PanelWire.FRAME_HEADER_BYTES) {
            throw new IllegalArgumentException("frame header truncated");
        }
        byte type = input.readByte();
        long requestId = input.readLong();
        int metaLength = input.readInt();
        if (metaLength < 0 || metaLength > PanelWire.MAX_META_BYTES || metaLength > input.readableBytes()) {
            throw new IllegalArgumentException("invalid frame metadata length");
        }
        byte[] metaBytes = new byte[metaLength];
        input.readBytes(metaBytes);
        JsonObject meta = metaLength == 0 ? new JsonObject()
                : JsonParser.parseString(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        int bodyLength = input.readableBytes();
        if (type == PanelFrame.REQUEST && bodyLength > PanelWire.MAX_REQUEST_BODY) {
            throw new TooLongFrameException("request body too large");
        }
        byte[] body = new byte[bodyLength];
        input.readBytes(body);
        out.add(new PanelFrame(type, requestId, meta, body));
    }
}
