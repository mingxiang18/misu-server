package com.misu.net.protocol;

import lombok.Data;

import java.util.Map;

/**
 *
 */
@Data
public class NatxMessage {

    private NatxMessageType type;
    private Map<String, Object> metaData;
    private byte[] data;
}
