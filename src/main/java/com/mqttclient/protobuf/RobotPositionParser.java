package com.mqttclient.protobuf;

import com.mqttclient.config.Constants;
import com.mqttclient.protobuf.gen.CustomByteBlockProto.CustomByteBlock;
import com.mqttclient.protobuf.gen.RobotPositionProto.RobotPosition;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public final class RobotPositionParser {
    
    private RobotPositionParser() {
    }

    public static RobotPosition parsePayload(byte[] mqttPayload) throws InvalidProtocolBufferException {
        RobotPosition position = RobotPosition.parseFrom(mqttPayload);
        return position;
    }
}


