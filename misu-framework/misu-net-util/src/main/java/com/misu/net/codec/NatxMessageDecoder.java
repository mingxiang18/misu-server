package com.misu.net.codec;

import com.alibaba.fastjson2.JSON;
import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.CharsetUtil;

import java.util.List;
import java.util.Map;

/**
 *
 */
public class NatxMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List out) throws Exception {

        int type = msg.readInt();
        NatxMessageType natxMessageType = NatxMessageType.valueOf(type);

        int metaDataLength = msg.readInt();
        CharSequence metaDataString = msg.readCharSequence(metaDataLength, CharsetUtil.UTF_8);
        Map<String, Object> metaData = JSON.parseObject(metaDataString.toString());

        byte[] data = null;
        if (msg.isReadable()) {
            data = ByteBufUtil.getBytes(msg);
        }

        NatxMessage natxMessage = new NatxMessage();
        natxMessage.setType(natxMessageType);
        natxMessage.setMetaData(metaData);
        natxMessage.setData(data);

        out.add(natxMessage);
    }

}
