package com.mqttclient.video;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证从 Annex-B 提取 SPS/PPS 并构建 avcC 的逻辑。
 */
class AvccExtractorTest {

    /** 4 字节起始码 + NAL 头 + 载荷。 */
    private static byte[] nal(int nalType, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(1);
        out.write(0x60 | (nalType & 0x1F)); // 简化的 NAL 头，type 在低 5 位
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    @Test
    void buildsAvccFromSpsAndPps() {
        // SPS body: nalHeader + profile(66) + compat(0) + level(30) + 其余
        byte[] spsPayload = {66, 0, 30, 10, 20};
        byte[] ppsPayload = {5, 6};
        byte[] sps = nal(7, spsPayload);
        byte[] pps = nal(8, ppsPayload);
        byte[] stream = concat(sps, pps);

        byte[] avcc = AvccExtractor.extractAvcc(stream);
        assertNotNull(avcc, "应成功构建 avcC");

        // configurationVersion
        assertEquals(0x01, avcc[0] & 0xFF);
        // profile_idc = SPS body 的第 2 字节 (spsBody[1]) = profile = 66
        assertEquals(66, avcc[1] & 0xFF);
        // level_idc = spsBody[3] = 30
        assertEquals(30, avcc[3] & 0xFF);
        // lengthSizeMinusOne
        assertEquals(0xFF, avcc[4] & 0xFF);
        // numSPS marker
        assertEquals(0xE1, avcc[5] & 0xFF);
    }

    @Test
    void returnsNullWhenPpsMissing() {
        byte[] sps = nal(7, new byte[]{66, 0, 30, 1});
        assertNull(AvccExtractor.extractAvcc(sps));
    }

    @Test
    void returnsNullWhenNoStartCode() {
        assertNull(AvccExtractor.extractAvcc(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    void findStartCodeDetects3And4Byte() {
        byte[] threeB = {9, 9, 0, 0, 1, 5};
        assertEquals(2, AvccExtractor.findStartCode(threeB, 0));

        byte[] fourB = {0, 0, 0, 1, 5};
        assertEquals(0, AvccExtractor.findStartCode(fourB, 0));
    }
}
