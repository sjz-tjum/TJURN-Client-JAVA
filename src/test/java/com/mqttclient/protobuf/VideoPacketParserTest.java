package com.mqttclient.protobuf;

import com.mqttclient.protobuf.VideoPacketParser.VideoPacket;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 <Hq290s 解包逻辑与 Python struct.unpack 一致。
 */
class VideoPacketParserTest {

    /** 构造一个 300 字节的 little-endian 结构体：seq_id(2) + timestamp(8) + data(290)。 */
    private static byte[] buildRaw(int seqId, long timestamp, byte[] chunk) {
        ByteBuffer buf = ByteBuffer.allocate(300).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) (seqId & 0xFFFF));
        buf.putLong(timestamp);
        buf.put(chunk);
        return buf.array();
    }

    @Test
    void unpacksLittleEndianStruct() {
        byte[] chunk = new byte[290];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i & 0xFF);
        }
        byte[] raw = buildRaw(12345, 1_700_000_000_000_000L, chunk);

        VideoPacket p = VideoPacketParser.unpack(raw);
        assertEquals(12345, p.seqId());
        assertEquals(1_700_000_000_000_000L, p.timestamp());
        assertArrayEquals(chunk, p.h264Chunk());
    }

    @Test
    void seqIdIsUnsigned() {
        // 65535 作为无符号 uint16，不应被解读为 -1
        byte[] raw = buildRaw(65535, 0L, new byte[290]);
        VideoPacket p = VideoPacketParser.unpack(raw);
        assertEquals(65535, p.seqId());
    }

    @Test
    void wrongLengthReturnsNull() {
        assertNull(VideoPacketParser.unpack(new byte[299]));
        assertNull(VideoPacketParser.unpack(new byte[301]));
        assertNull(VideoPacketParser.unpack(null));
    }
}
