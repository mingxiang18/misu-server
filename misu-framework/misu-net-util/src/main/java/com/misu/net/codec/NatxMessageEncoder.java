package com.misu.net.codec;

import com.alibaba.fastjson2.JSON;
import com.misu.net.protocol.NatxMessage;
import com.misu.net.protocol.NatxMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.CharsetUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 *
 */
public class NatxMessageEncoder extends MessageToByteEncoder<NatxMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, NatxMessage msg, ByteBuf out) throws Exception {

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream)) {

            NatxMessageType natxMessageType = msg.getType();
            dataOutputStream.writeInt(natxMessageType.getCode());

            byte[] metaDataBytes = JSON.toJSONString(msg.getMetaData()).getBytes(CharsetUtil.UTF_8);
            dataOutputStream.writeInt(metaDataBytes.length);
            dataOutputStream.write(metaDataBytes);

            if (msg.getData() != null && msg.getData().length > 0) {
                dataOutputStream.write(msg.getData());
            }

            byte[] data = byteArrayOutputStream.toByteArray();
            out.writeInt(data.length);
            out.writeBytes(data);
        }

    }

}
