package com.klnon.sablepanel.panel.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;

import java.net.InetSocketAddress;

/** Validates request size from the frame header before Netty accumulates the complete body. */
final class PanelFrameDecoder extends LengthFieldBasedFrameDecoder {
    private static final int TYPE_OFFSET = 0;
    private static final int META_LENGTH_OFFSET = 1 + Long.BYTES;
    private final boolean trustedResponseSource;

    PanelFrameDecoder(boolean trustedResponseSource) {
        super(PanelWire.MAX_FRAME_BYTES, 0, PanelWire.LENGTH_FIELD_BYTES, 0, PanelWire.LENGTH_FIELD_BYTES);
        this.trustedResponseSource = trustedResponseSource;
    }

    /**
     * 长度前缀拆帧和整帧上限交给父类;这里只在 body 攒起来之前先看一眼头部,
     * 把元数据长度非法、以及不被允许的超大 body 提前打掉 —— 否则要先缓冲满整帧才发现。
     */
    @Override
    protected Object decode(ChannelHandlerContext context, ByteBuf input) throws Exception {
        validateHeader(context, input);
        return super.decode(context, input);
    }

    private void validateHeader(ChannelHandlerContext context, ByteBuf input) {
        if (input.readableBytes() < PanelWire.LENGTH_FIELD_BYTES) return;
        int frameStart = input.readerIndex();
        long payloadLength = input.getUnsignedInt(frameStart);
        if (payloadLength < PanelWire.FRAME_HEADER_BYTES) {
            throw new CorruptedFrameException("frame header truncated");
        }
        if (input.readableBytes() < PanelWire.LENGTH_FIELD_BYTES + PanelWire.FRAME_HEADER_BYTES) return;

        int payloadStart = frameStart + PanelWire.LENGTH_FIELD_BYTES;
        byte type = input.getByte(payloadStart + TYPE_OFFSET);
        int metaLength = input.getInt(payloadStart + META_LENGTH_OFFSET);
        if (metaLength < 0 || metaLength > PanelWire.MAX_META_BYTES
                || PanelWire.FRAME_HEADER_BYTES + (long) metaLength > payloadLength) {
            throw new CorruptedFrameException("invalid frame metadata length");
        }
        long bodyLength = payloadLength - PanelWire.FRAME_HEADER_BYTES - metaLength;
        if (bodyLength > PanelNet.MAX_REQUEST_BODY && !allowsLargeBody(context, type)) {
            throw new TooLongFrameException("request body too large");
        }
    }

    private boolean allowsLargeBody(ChannelHandlerContext context, byte type) {
        if (type != PanelFrame.RESPONSE) return false;
        if (this.trustedResponseSource) return true;
        if (!(context.channel().remoteAddress() instanceof InetSocketAddress remote)) return false;
        return remote.getAddress().isLoopbackAddress();
    }
}
