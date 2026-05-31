package com.nubbank.baas.fep.server;

import com.nubbank.baas.fep.config.FepProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Netty-based ISO 8583 TCP server.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start()} — called by Spring on context startup ({@code @PostConstruct});
 *       binds to {@code fep.tcp-port} (use {@code 0} in tests for an ephemeral port).</li>
 *   <li>{@link #getBoundPort()} — returns the actual OS-assigned port; useful when
 *       {@code tcp-port=0} is configured for integration tests.</li>
 *   <li>{@link #stop()} — called by Spring on context shutdown ({@code @PreDestroy});
 *       closes the server channel and shuts down both event loop groups.</li>
 * </ul>
 *
 * <p>The framing protocol (2-byte big-endian length prefix) is configured by
 * {@link FepServerInitializer}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FepTcpServer {

    private final FepProperties       props;
    private final FepServerInitializer initializer;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel        channel;

    @PostConstruct
    public void start() {
        boss   = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();

        channel = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer)
                .bind(props.tcpPort())
                .syncUninterruptibly()
                .channel();

        log.info("FEP TCP server bound on port {}", getBoundPort());
    }

    /**
     * Returns the actual port the server is listening on.
     * When {@code fep.tcp-port=0} is configured (e.g. in tests), the OS assigns an
     * ephemeral port; this method reflects the real bound port after {@link #start()}.
     */
    public int getBoundPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @PreDestroy
    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
        log.info("FEP TCP server stopped");
    }
}
