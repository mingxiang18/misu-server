package com.misu.net.handler;

import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Getter
@Slf4j
public class NatxCommonHandler extends ChannelInboundHandlerAdapter {

    protected ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
       log.error("Exception caught ...", cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                log.error("Read idle loss connection.");
                ctx.close();
            } else if (e.state() == IdleState.WRITER_IDLE) {
                NatxMessage natxMessage = new NatxMessage();
                natxMessage.setType(NatxMessageType.KEEPALIVE);
                ctx.writeAndFlush(natxMessage);
            }
        }
    }
}
