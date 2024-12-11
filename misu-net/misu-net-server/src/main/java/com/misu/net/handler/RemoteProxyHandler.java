package com.misu.net.handler;

import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;
import java.util.HashMap;

/**
 *
 */
public class RemoteProxyHandler extends NatxCommonHandler {

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        NatxMessage message = new NatxMessage();
        message.setType(NatxMessageType.CONNECTED);
        HashMap<String, Object> metaData = new HashMap<>();
        metaData.put("channelId", ctx.channel().id().asLongText());
        message.setMetaData(metaData);

        // 获取提供远程连接的端口号
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        int localPort = localAddress.getPort();
        // 绑定natx客户端
        Channel natxChannel = RemoteConnectionManagement.findAndBindNatxChannel(localPort, ctx.channel());
        System.out.println("激活的客户端channel:" + natxChannel.id());
        natxChannel.writeAndFlush(message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        NatxMessage message = new NatxMessage();
        message.setType(NatxMessageType.DISCONNECTED);
        HashMap<String, Object> metaData = new HashMap<>();
        metaData.put("channelId", ctx.channel().id().asLongText());
        message.setMetaData(metaData);

        // 获取提供远程连接的端口号
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        int localPort = localAddress.getPort();
        //对应的natx客户端执行本地端口断开
        try {
            Channel natxChannel = RemoteConnectionManagement.getBindNatxChannel(ctx.channel());
            if (natxChannel != null) {
                natxChannel.writeAndFlush(message);
                natxChannel.close();
            }
            System.out.println("解绑写入数据的客户端channel:" + natxChannel.id());
        }finally {
            RemoteConnectionManagement.unbindNatxChannel(localPort, ctx.channel());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        byte[] data = (byte[]) msg;
        NatxMessage message = new NatxMessage();
        message.setType(NatxMessageType.DATA);
        message.setData(data);
        HashMap<String, Object> metaData = new HashMap<>();
        metaData.put("channelId", ctx.channel().id().asLongText());
        message.setMetaData(metaData);

        //转发到对应的natx客户端
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        int localPort = localAddress.getPort();
        try {
            Channel natxChannel = RemoteConnectionManagement.getBindNatxChannel(ctx.channel());
            System.out.println("写入数据的客户端channel:" + natxChannel.id());
            natxChannel.writeAndFlush(message);
        }finally {
//            RemoteConnectionManagement.unbindNatxChannel(localPort, ctx.channel());
        }
    }
}
