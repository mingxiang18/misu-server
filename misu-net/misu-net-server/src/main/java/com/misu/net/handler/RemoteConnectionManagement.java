package com.misu.net.handler;

import com.misu.net.net.TcpServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.SneakyThrows;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class RemoteConnectionManagement {

    /**
     * 注册代理端口channel的管理map
     */
    private static final Map<Integer, ChannelGroup> remoteProxyChannelMap = new ConcurrentHashMap<>();

    /**
     * natx的channel队列管理map
     */
    private static final Map<Integer, BlockingQueue<Channel>> natxChannelMap = new ConcurrentHashMap<>();

    /**
     * 注册代理端口channel的锁
     */
    private static final ReentrantLock remoteProxyRegisterLock = new ReentrantLock();

    /**
     * 注册natx的channel锁
     */
    private static final ReentrantLock natxRegisterLock = new ReentrantLock();

    /**
     * 代理端口channel与natx的channel的绑定map
     * key: 代理端口channel
     * value: natx的channel
     */
    private static final Map<Channel, Channel> channelBindMap = new ConcurrentHashMap<>();

    /**
     * 注册代理端口的channel
     */
    @SneakyThrows
    public static void registerRemoteProxyPortChannel(Integer port) {
        remoteProxyRegisterLock.lock();
        try {
            if (!remoteProxyChannelMap.containsKey(port)) {
                ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
                remoteProxyChannelMap.put(port, channelGroup);

                TcpServer remoteConnectionServer = new TcpServer();
                remoteConnectionServer.bind(port, new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
                                new ByteArrayDecoder(), new ByteArrayEncoder(), new RemoteProxyHandler());

                        //添加到channel组
                        channelGroup.add(ch);
                    }
                });

            }
        }finally {
            remoteProxyRegisterLock.unlock();
        }
    }

    /**
     * 注册Natx的channel
     */
    @SneakyThrows
    public static void registerNatxChannel(Integer port, Channel channel) {
        natxRegisterLock.lock();
        try {
            //如果不存在则默认设置一个
            if (!natxChannelMap.containsKey(port)) {
                BlockingQueue<Channel> queue = new LinkedBlockingQueue<>();
                natxChannelMap.put(port, queue);
            }

            natxChannelMap.get(port).offer(channel);
        }finally {
            natxRegisterLock.unlock();
        }
    }

    /**
     * 移除Natx的channel
     */
    @SneakyThrows
    public static void removeNatxChannel(Integer remoteProxyPort, Channel channel) {
        BlockingQueue<Channel> blockingQueue = natxChannelMap.get(remoteProxyPort);
        if (blockingQueue != null) {
            blockingQueue.remove(channel);
        }
        //如果有绑定了该Natx的channel的远程代理，解绑并关闭连接
        for (Map.Entry<Channel, Channel> bindChannelEntry : channelBindMap.entrySet()) {
            if (channel.equals(bindChannelEntry.getValue())) {
                Channel remoteProxyChannel = bindChannelEntry.getKey();
                remoteProxyChannel.close();
                channelBindMap.remove(bindChannelEntry.getKey());
            }
        }
    }

    /**
     * 寻找空闲的Natx的channel，并与代理端口channel绑定
     */
    public static Channel findAndBindNatxChannel(Integer remoteProxyPort, Channel remoteProxyChannel) {
        BlockingQueue<Channel> channelQueue = natxChannelMap.get(remoteProxyPort);

        if (channelQueue == null) {
            return null;  // 如果没有通道队列，则返回 null
        }

        // 从队列中获取一个空闲的 channel
        System.out.println("当前空闲的连接：" + channelQueue.size());
        Channel channel = channelQueue.poll();  // 非阻塞获取，如果没有空闲通道则返回 null
        if (channel != null) {
            channelBindMap.put(remoteProxyChannel, channel);
            return channel;
        }

        // 如果没有空闲的通道，返回 null
        return null;
    }

    /**
     * 获取绑定的natx客户端channel
     */
    public static Channel getBindNatxChannel(Channel remoteProxyChannel) {
        return channelBindMap.get(remoteProxyChannel);
    }

    /**
     * 解绑的natx客户端channel
     */
    public static void unbindNatxChannel(Integer remoteProxyPort, Channel remoteProxyChannel) {
        Channel natxChannel = channelBindMap.get(remoteProxyChannel);
        channelBindMap.remove(remoteProxyChannel);
    }

    /**
     * 获取远程代理
     */
    public static Channel getRemoteProxyChannel(String channelId) {
        for (Map.Entry<Integer, ChannelGroup> groupEntry : remoteProxyChannelMap.entrySet()) {
            ChannelGroup channelGroup = groupEntry.getValue();
            for (Channel channel : channelGroup) {
                if (channelId.equals(channel.id().asLongText())) {
                    return channel;
                }
            }
        }
        return null;
    }
}
