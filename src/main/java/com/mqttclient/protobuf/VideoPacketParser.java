package com.mqttclient.protobuf;

import com.mqttclient.config.Constants;
import com.mqttclient.protobuf.gen.CustomByteBlockProto.CustomByteBlock;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
 * 视频包解析工具。
 */
public final class VideoPacketParser {

    private VideoPacketParser() {
    }

    /** 解析后的视频包结构，对应 struct.unpack 的三元组。 */
    public record VideoPacket(int seqId, long timestamp, byte[] h264Chunk) {
    }

    /**
     * protobuf 反序列化，返回内部 data。
     *
     * @param mqttPayload MQTT 原始载荷
     * @return 真正的视频包数据（应为 300 字节）
     */
    public static byte[] parseVideoPayload(byte[] mqttPayload) throws InvalidProtocolBufferException {
        CustomByteBlock block = CustomByteBlock.parseFrom(mqttPayload);
        return block.getData().toByteArray();
    }

    /**
     * 解出 VideoStreamData 结构体。
     * 对应 {@code struct.unpack('<Hq290s', raw_data)}。
     *
     * @param rawData 300 字节的视频包数据
     * @return 解析结果；长度不符时返回 null
     */
    public static VideoPacket unpack(byte[] rawData) {
        if (rawData == null || rawData.length != Constants.VIDEO_DATA_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
        // uint16：读 short 后与 0xFFFF 求与，得到无符号值
        int seqId = buf.getShort() & 0xFFFF;
        long timestamp = buf.getLong();
        byte[] h264Chunk = new byte[Constants.H264_CHUNK_SIZE];
        buf.get(h264Chunk);
        return new VideoPacket(seqId, timestamp, h264Chunk);
    }

    /**
     * 从 MQTT 原始载荷解析出结构体。
     *
     * @return 解析结果，失败或长度异常返回 null
     */
    public static VideoPacket parse(byte[] mqttPayload) throws InvalidProtocolBufferException {
        return unpack(parseVideoPayload(mqttPayload));
    }
}
