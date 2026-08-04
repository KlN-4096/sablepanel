package com.klnon.sablepanel.panel.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;

import java.net.InetSocketAddress;
import java.util.List;

/** Validates request size from the frame header before Netty accumulates the complete body. */
final class PanelFrameDecoder extends ByteToMessageDecoder {
    private static final int TYPE_OFFSET = 0;
    private static final int META_LENGTH_OFFSET = 1 + Long.BYTES;
    private final boolean trustedResponseSource;

    PanelFrameDecoder(boolean trustedResponseSource) {
        this.trustedResponseSource = trustedResponseSource;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> out) {
        if (input.readableBytes() < PanelWire.LENGTH_FIELD_BYTES) return;
        int frameStart = input.readerIndex();
        long payloadLength = input.getUnsignedInt(frameStart);
        long totalLength = PanelWire.LENGTH_FIELD_BYTES + payloadLength;
        if (payloadLength < PanelWire.FRAME_HEADER_BYTES) {
            throw new CorruptedFrameException("frame header truncated");
        }
        if (totalLength > PanelWire.MAX_FRAME_BYTES) throw new TooLongFrameException("frame too large");
        if (input.readableBytes() < PanelWire.LENGTH_FIELD_BYTES + PanelWire.FRAME_HEADER_BYTES) return;

        int payloadStart = frameStart + PanelWire.LENGTH_FIELD_BYTES;
        byte type = input.getByte(payloadStart + TYPE_OFFSET);
        int metaLength = input.getInt(payloadStart + META_LENGTH_OFFSET);
        if (metaLength < 0 || metaLength > PanelWire.MAX_META_BYTES
                || PanelWire.FRAME_HEADER_BYTES + (long) metaLength > payloadLength) {
            throw new CorruptedFrameException("invalid frame metadata length");
        }
        long bodyLength = payloadLength - PanelWire.FRAME_HEADER_BYTES - metaLength;
        if (bodyLength > PanelWire.MAX_REQUEST_BODY && !allowsLargeBody(context, type)) {
            throw new TooLongFrameException("request body too large");
        }
        if (input.readableBytes() < totalLength) return;

        input.skipBytes(PanelWire.LENGTH_FIELD_BYTES);
        out.add(input.readRetainedSlice((int) payloadLength));
    }

    private boolean allowsLargeBody(ChannelHandlerContext context, byte type) {
        if (type != PanelFrame.RESPONSE && type != PanelFrame.ERROR) return false;
        if (this.trustedResponseSource) return true;
        if (!(context.channel().remoteAddress() instanceof InetSocketAddress remote)) return false;
        return remote.getAddress().isLoopbackAddress();
    }
}
