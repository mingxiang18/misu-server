package com.misu.net;

import com.misu.net.codec.NatxMessageDecoder;
import com.misu.net.codec.NatxMessageEncoder;
import com.misu.net.config.NatxConfigEntity;
import com.misu.net.handler.NatxClientHandler;
import com.misu.net.net.TcpConnection;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;

/**
 * Natx客户端
 */
@Slf4j
public class NatxClient {

    public void connect(NatxConfigEntity natxConfigEntity) throws IOException, InterruptedException {
        TcpConnection natxConnection = new TcpConnection();
        ChannelFuture future = natxConnection.connect(natxConfigEntity.getServerAddress(), natxConfigEntity.getServerPort(), new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                NatxClientHandler natxClientHandler = new NatxClientHandler(natxConfigEntity.getRemotePort(), natxConfigEntity.getServerSecret(),
                        natxConfigEntity.getProxyAddress(), natxConfigEntity.getProxyPort());
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
                        new NatxMessageDecoder(), new NatxMessageEncoder(),
                        new IdleStateHandler(60, 30, 0), natxClientHandler);
            }
        });

        // channel close retry connect
        future.addListener(future1 -> new Thread() {
            @Override
            public void run() {
                while (true) {
                    //先休眠10秒
                    try {
                        Thread.sleep(natxConfigEntity.getReconnectTime());
                    } catch (InterruptedException e) {
                        log.error("线程休眠异常", e);
                    }
                    try {
                        //连接服务端，如果成功则退出循环
                        connect(natxConfigEntity);
                        break;
                    } catch (Exception e) {
                        log.error("连接服务端出现异常", e);
                    }
                }
            }
        }.start());
    }
}
