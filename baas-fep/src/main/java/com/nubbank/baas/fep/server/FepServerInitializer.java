package com.nubbank.baas.fep.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Netty {@link ChannelInitializer} that wires the ISO 8583 2-byte length-framing
 * codec and the shared message handler into each accepted client channel.
 *
 * <p>Framing convention (matches Thales payShield / most ISO 8583 host connections):
 * <pre>
 *   [2-byte big-endian length][ISO 8583 payload]
 * </pre>
 * {@link LengthFieldBasedFrameDecoder} strips the 2-byte header before passing the
 * payload to the next handler.  {@link LengthFieldPrepender} prepends the 2-byte header
 * on every outbound write.
 *
 * <p>Pipeline order on each accepted connection:
 * <ol>
 *   <li>LengthFieldBasedFrameDecoder — inbound: strip 2-byte length header, accumulate frame</li>
 *   <li>LengthFieldPrepender         — outbound: prepend 2-byte length header</li>
 *   <li>FepMessageHandler            — inbound: decode ISO 8583, route, encode response</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FepServerInitializer extends ChannelInitializer<SocketChannel> {

    private final FepMessageHandler handler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                // Inbound: accumulate bytes until a full frame arrives, then strip the 2-byte
                // length prefix before passing to downstream handlers.
                // Parameters: maxFrameLength=65535, lengthFieldOffset=0, lengthFieldLength=2,
                //              lengthAdjustment=0, initialBytesToStrip=2 (strip the header).
                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))

                // Outbound: prepend a 2-byte big-endian length header to every write.
                .addLast(new LengthFieldPrepender(2))

                // Inbound: unpack ISO 8583, route through MessageRouter, pack and write reply.
                // FepMessageHandler is @ChannelHandler.Sharable — one bean instance serves all channels.
                .addLast(handler);
    }
}
