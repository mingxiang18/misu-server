package com.misu.net.handler;

import com.misu.net.exception.NatxException;
import com.misu.net.net.TcpServer;
import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

/**
 *
 */
@Slf4j
public class NatxServerHandler extends NatxCommonHandler {

    private final TcpServer remoteConnectionServer = new TcpServer();

    private final String secret;
    private int port;

    private boolean register = false;

    public NatxServerHandler(String secret) {
        this.secret = secret;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        NatxMessage natxMessage = (NatxMessage) msg;
        if (natxMessage.getType() == NatxMessageType.REGISTER) {
            processRegister(ctx, natxMessage);
        } else if (register) {
            if (natxMessage.getType() == NatxMessageType.DISCONNECTED) {
                processDisconnected(natxMessage);
            } else if (natxMessage.getType() == NatxMessageType.DATA) {
                processData(natxMessage);
            } else if (natxMessage.getType() == NatxMessageType.KEEPALIVE) {
                // 心跳包, 不处理
            } else {
                throw new NatxException("Unknown type: " + natxMessage.getType());
            }
        } else {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        remoteConnectionServer.close();

        //连接管理移除当前channel
        RemoteConnectionManagement.removeNatxChannel(port, ctx.channel());

        if (register) {
            log.info("Stop server on port: " + port);
        }
    }

    /**
     * if natxMessage.getType() == NatxMessageType.REGISTER
     */
    private void processRegister(ChannelHandlerContext ctx, NatxMessage natxMessage) {
        HashMap<String, Object> metaData = new HashMap<>();

        String secret = natxMessage.getMetaData().get("secret").toString();
        if (this.secret != null && !this.secret.equals(secret)) {
            metaData.put("success", false);
            metaData.put("reason", "Token is wrong");
        } else {
            int port = (int) natxMessage.getMetaData().get("port");

            try {
                //把自己注册到连接管理器
                RemoteConnectionManagement.registerNatxChannel(port, ctx.channel());
                //把需要远程代理的端口注册到连接管理器
                RemoteConnectionManagement.registerRemoteProxyPortChannel(port);

                metaData.put("success", true);
                this.port = port;
                register = true;
                log.info("Register success, start server on port: " + port);
            } catch (Exception e) {
                metaData.put("success", false);
                metaData.put("reason", e.getMessage());
                e.printStackTrace();
            }
        }

        NatxMessage sendBackMessage = new NatxMessage();
        sendBackMessage.setType(NatxMessageType.REGISTER_RESULT);
        sendBackMessage.setMetaData(metaData);
        ctx.writeAndFlush(sendBackMessage);

        if (!register) {
            log.error("Client register error: " + metaData.get("reason"));
            ctx.close();
        }
    }

    /**
     * if natxMessage.getType() == NatxMessageType.DATA
     */
    private void processData(NatxMessage natxMessage) {
        String channelId = natxMessage.getMetaData().get("channelId").toString();

        //把响应数据写入代理端口返回
        Channel remoteProxyChannel = RemoteConnectionManagement.getRemoteProxyChannel(channelId);
        if (remoteProxyChannel != null) {
            remoteProxyChannel.writeAndFlush(natxMessage.getData());
        }
    }

    /**
     * if natxMessage.getType() == NatxMessageType.DISCONNECTED
     * @param natxMessage
     */
    private void processDisconnected(NatxMessage natxMessage) {
        String channelId = natxMessage.getMetaData().get("channelId").toString();

        //关闭远程端口连接
        Channel remoteProxyChannel = RemoteConnectionManagement.getRemoteProxyChannel(channelId);
        if (remoteProxyChannel != null) {
            remoteProxyChannel.close();
        }
    }
}
