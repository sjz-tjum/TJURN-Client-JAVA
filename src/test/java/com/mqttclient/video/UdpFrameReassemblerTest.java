package com.mqttclient.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UDP 分片帧重组器单元测试。
 */
class UdpFrameReassemblerTest {

    @Test
    void assemblesOutOfOrderFragments() {
        UdpFrameReassembler r = new UdpFrameReassembler(1000, s -> {});
        byte[] f0 = {1, 2, 3, 4};
        byte[] f1 = {5, 6, 7, 8};
        byte[] f2 = {9, 10, 11, 12};

        // 乱序到达：frag2 → frag0 → frag1
        assertNull(r.addFragment(0, 2, f2, 12));
        assertNull(r.addFragment(0, 0, f0, 12));
        byte[] out = r.addFragment(0, 1, f1, 12);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, out);
        assertEquals(0, r.pendingCount(), "帧收齐后应从缓存移除");
    }

    @Test
    void duplicateFragmentNotDoubleCounted() {
        UdpFrameReassembler r = new UdpFrameReassembler(1000, s -> {});
        byte[] f0 = {1, 2, 3, 4};
        byte[] f1 = {5, 6, 7, 8};

        assertNull(r.addFragment(0, 0, f0, 8));
        assertNull(r.addFragment(0, 0, f0, 8));   // 重复发送 frag0，不应重复计入
        byte[] out = r.addFragment(0, 1, f1, 8);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, out);
    }

    @Test
    void interleavedFramesKeptSeparate() {
        UdpFrameReassembler r = new UdpFrameReassembler(1000, s -> {});
        // 帧 0 和帧 1 的分片交错到达
        assertNull(r.addFragment(0, 0, new byte[]{1, 2}, 4));
        assertNull(r.addFragment(1, 0, new byte[]{9, 9}, 4));
        byte[] out1 = r.addFragment(1, 1, new byte[]{9, 9}, 4);  // 帧1先收齐
        assertArrayEquals(new byte[]{9, 9, 9, 9}, out1);

        byte[] out0 = r.addFragment(0, 1, new byte[]{3, 4}, 4);  // 帧0后收齐
        assertArrayEquals(new byte[]{1, 2, 3, 4}, out0);
        assertEquals(0, r.pendingCount());
    }

    @Test
    void zeroTotalBytesNeverCompletesUntilValid() {
        UdpFrameReassembler r = new UdpFrameReassembler(1000, s -> {});
        // 首包 totalBytes=0（未知），后续包补上有效值
        assertNull(r.addFragment(0, 0, new byte[]{1, 2}, 0));
        assertEquals(1, r.pendingCount());
        byte[] out = r.addFragment(0, 1, new byte[]{3, 4, 5, 6}, 6);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, out);
    }

    @Test
    void staleFramesCleanedAndCounted() {
        StringBuilder warnings = new StringBuilder();
        UdpFrameReassembler r = new UdpFrameReassembler(1000, warnings::append);
        r.addFragment(0, 0, new byte[]{1}, 10);  // 不完整帧
        r.addFragment(1, 0, new byte[]{1}, 10);
        assertEquals(2, r.pendingCount());

        int removed = r.cleanupStale(System.currentTimeMillis() + 2000);
        assertEquals(2, removed);
        assertEquals(0, r.pendingCount());
        assertEquals(true, warnings.length() > 0, "应产生超时告警");
    }

    @Test
    void freshFramesNotCleaned() {
        UdpFrameReassembler r = new UdpFrameReassembler(1000, s -> {});
        r.addFragment(0, 0, new byte[]{1}, 10);
        int removed = r.cleanupStale(System.currentTimeMillis());
        assertEquals(0, removed);
        assertEquals(1, r.pendingCount());
    }
}
