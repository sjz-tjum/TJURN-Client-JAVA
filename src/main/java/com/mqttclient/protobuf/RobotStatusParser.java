package com.mqttclient.protobuf;

import com.mqttclient.protobuf.gen.RobotDynamicStatusProto.RobotDynamicStatus;
import com.mqttclient.protobuf.gen.RobotStaticStatusProto.RobotStaticStatus;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * 解析机器人静态状态（RobotStaticStatus）和动态状态（RobotDynamicStatus）。
 */
public final class RobotStatusParser {

    private RobotStatusParser() {
    }

    public static RobotStaticStatus parseStaticStatus(byte[] mqttPayload)
            throws InvalidProtocolBufferException {
        return RobotStaticStatus.parseFrom(mqttPayload);
    }

    public static RobotDynamicStatus parseDynamicStatus(byte[] mqttPayload)
            throws InvalidProtocolBufferException {
        return RobotDynamicStatus.parseFrom(mqttPayload);
    }
}
