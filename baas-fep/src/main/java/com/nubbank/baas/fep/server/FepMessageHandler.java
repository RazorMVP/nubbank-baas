package com.nubbank.baas.fep.server;

import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.router.MessageRouter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Netty inbound handler for ISO 8583 messages.
 *
 * <p>Receives a fully-assembled ISO 8583 payload (2-byte length header already stripped
 * by {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder}), decodes it using
 * {@link IsoMessageFactory}, routes it through {@link MessageRouter}, packs the
 * response, and writes it back to the channel (the outbound
 * {@link io.netty.handler.codec.LengthFieldPrepender} in the pipeline adds the 2-byte
 * length header automatically).
 *
 * <p><strong>PAN safety rule:</strong> this handler MUST NEVER log message contents,
 * raw bytes, or any ISO 8583 field values.  On error, only {@code e.getMessage()} is
 * logged — never the payload.
 *
 * <p>This bean is {@link ChannelHandler.Sharable} so that Netty can add the single
 * Spring-managed instance to every accepted channel's pipeline without cloning it.
 * Netty enforces this annotation at runtime — without it the server throws
 * {@code ChannelPipelineException} when the second connection arrives.
 */
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
@Slf4j
public class FepMessageHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final IsoMessageFactory iso;
    private final MessageRouter     router;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        // Extract raw ISO 8583 payload bytes (length header already stripped by framing decoder).
        byte[] raw = new byte[in.readableBytes()];
        in.readBytes(raw);

        // NOTE: do NOT log 'raw' — it contains the full ISO 8583 frame including the PAN.
        ISOMsg response;
        try {
            ISOMsg request = iso.unpack(raw);
            response = router.route(request);
        } catch (Exception e) {
            // Log only the exception message — never the raw bytes or any ISO field.
            log.warn("FEP processing error: {}", e.getMessage());
            response = router.systemError();
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(iso.pack(response)));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Log transport/channel errors (e.g. connection reset) without exposing message data.
        log.warn("FEP channel error: {}", cause.getMessage());
        ctx.close();
    }
}
