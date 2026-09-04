package com.mqttclient.protobuf;

import com.mqttclient.config.Constants;
import com.mqttclient.protobuf.gen.CustomByteBlockProto.CustomByteBlock;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
 * Video packet parsing utilities.
 */
public final class VideoPacketParser {

    private VideoPacketParser() {
    }

    /** A parsed video packet, mirroring the struct.unpack tuple. */
    public record VideoPacket(int seqId, long timestamp, byte[] h264Chunk) {
    }

    /**
     * Deserializes the protobuf message and returns its inner data.
     *
     * @param mqttPayload raw MQTT payload
     * @return the actual video packet data (expected to be 300 bytes)
     */
    public static byte[] parseVideoPayload(byte[] mqttPayload) throws InvalidProtocolBufferException {
        CustomByteBlock block = CustomByteBlock.parseFrom(mqttPayload);
        return block.getData().toByteArray();
    }

    /**
     * Decodes the VideoStreamData struct.
     * Mirrors {@code struct.unpack('<Hq290s', raw_data)}.
     *
     * @param rawData the 300-byte video packet data
     * @return the parsed result, or null if the length does not match
     */
    public static VideoPacket unpack(byte[] rawData) {
        if (rawData == null || rawData.length != Constants.VIDEO_DATA_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
        // uint16: read as short and mask with 0xFFFF to obtain the unsigned value
        int seqId = buf.getShort() & 0xFFFF;
        long timestamp = buf.getLong();
        byte[] h264Chunk = new byte[Constants.H264_CHUNK_SIZE];
        buf.get(h264Chunk);
        return new VideoPacket(seqId, timestamp, h264Chunk);
    }

    /**
     * Parses the struct from a raw MQTT payload.
     *
     * @return the parsed result, or null on failure or unexpected length
     */
    public static VideoPacket parse(byte[] mqttPayload) throws InvalidProtocolBufferException {
        return unpack(parseVideoPayload(mqttPayload));
    }
}
