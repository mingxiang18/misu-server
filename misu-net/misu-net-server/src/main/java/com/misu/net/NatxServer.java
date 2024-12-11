package com.misu.net;

import com.misu.net.codec.NatxMessageDecoder;
import com.misu.net.codec.NatxMessageEncoder;
import com.misu.net.handler.NatxCommonHandler;
import com.misu.net.net.TcpServer;
import com.misu.net.handler.NatxServerHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 */
public class NatxServer {
    public void start(int port, String secret) throws InterruptedException {

        TcpServer natxClientServer = new TcpServer();
        natxClientServer.bind(port, new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch)
                    throws Exception {
                NatxServerHandler natxServerHandler = new NatxServerHandler(secret);
                ch.pipeline().addLast(
                        // 添加带宽限制处理器
//                        new ChannelTrafficShapingHandler(10 * 1024 * 1024, 10 * 1024 * 1024), // 限制为10MB/s
                        new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
                        new NatxMessageDecoder(), new NatxMessageEncoder(),
                        new IdleStateHandler(60, 30, 0), natxServerHandler);
            }
        });
    }
}
