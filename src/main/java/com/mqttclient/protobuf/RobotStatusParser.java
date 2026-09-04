package com.mqttclient.protobuf;

import com.mqttclient.protobuf.gen.RobotDynamicStatusProto.RobotDynamicStatus;
import com.mqttclient.protobuf.gen.RobotStaticStatusProto.RobotStaticStatus;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Parses robot static status (RobotStaticStatus) and dynamic status (RobotDynamicStatus).
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
