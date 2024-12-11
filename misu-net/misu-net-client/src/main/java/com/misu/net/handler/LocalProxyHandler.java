package com.misu.net.handler;

import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.channel.ChannelHandlerContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

/**
 *
 */
public class LocalProxyHandler extends NatxCommonHandler {

    private NatxCommonHandler proxyHandler;
    private String remoteChannelId;

    public LocalProxyHandler(NatxCommonHandler proxyHandler, String remoteChannelId) {
        this.proxyHandler = proxyHandler;
        this.remoteChannelId = remoteChannelId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        byte[] data = (byte[]) msg;NatxMessage message = new NatxMessage();
        message.setType(NatxMessageType.DATA);
        message.setData(data);
        HashMap<String, Object> metaData = new HashMap<>();
        metaData.put("channelId", remoteChannelId);
        message.setMetaData(metaData);
        proxyHandler.getCtx().writeAndFlush(message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NatxMessage message = new NatxMessage();
        message.setType(NatxMessageType.DISCONNECTED);
        HashMap<String, Object> metaData = new HashMap<>();
        metaData.put("channelId", remoteChannelId);
        message.setMetaData(metaData);
        proxyHandler.getCtx().writeAndFlush(message);
    }
}
