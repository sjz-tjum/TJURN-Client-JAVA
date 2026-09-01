package com.mqttclient.video;

import com.mqttclient.config.Constants;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UDP HEVC 视频流端到端测试。
 *
 * <p>用 JavaCV 的 FFmpegFrameRecorder 实时编码一段真实 HEVC (Annex-B) 流，
 * 按 UDP 包格式分片发送到 {@link UdpVideoProcessor} 监听的端口，
 * 验证：UDP 接收 → 帧重组 → HEVC 解码 → 产出图像。
 *
 * <p>若环境缺少 libx265 编码器则跳过测试（不视为失败）。
 */
class UdpProcessorEndToEndTest {

    @AfterEach
    void restoreDefaultConfig() {
        System.clearProperty("mqtt.config");
        Constants.reload();
    }

    @Test
    void receivesReassemblesAndDecodesHevc() throws Exception {
        // 1. 用随机空闲端口，避免与其他测试/实例冲突
        int port;
        try (DatagramSocket s = new DatagramSocket(0)) {
            port = s.getLocalPort();
        }
        Path cfg = Files.createTempFile("mqtt-udp", ".json");
        Files.writeString(cfg, """
                {
                  "mqtt": { "host": "127.0.0.1", "port": 1883, "topics": ["CustomByteBlock"] },
                  "udp": { "host": "127.0.0.1", "port": %d,
                           "recvBufferSize": 65536, "frameTimeoutMs": 5000, "soTimeoutMs": 500 },
                  "video": { "width": 300, "height": 300, "displayScale": 0.5 },
                  "buffer": { "streamBufferSoftLimit": 5120, "streamBufferHardLimit": 500000,
                              "queueBacklogDrop": 5, "decoderThreadCount": 8, "renderIntervalMs": 2 }
                }
                """.formatted(port));
        System.setProperty("mqtt.config", cfg.toString());
        Constants.reload();

        // 2. 获取真实 HEVC 流：优先用本地样本文件，否则用 JavaCV 编码
        byte[] hevc = null;
        Path localSample = Path.of("test_sample.hevc");
        if (Files.exists(localSample)) {
            hevc = Files.readAllBytes(localSample);
            System.out.println("[TEST] 使用本地样本 test_sample.hevc (" + hevc.length + " B)");
        } else {
            try {
                hevc = encodeTestHevc();
            } catch (Exception e) {
                Assumptions.abort("无本地样本且无法编码 HEVC（缺少 libx265?）: " + e.getMessage());
                return;
            }
        }

        // 3. 切帧
        List<byte[]> frames = splitFrames(hevc);
        Assumptions.assumeTrue(!frames.isEmpty(), "HEVC 流未切出任何帧");

        // 4. 启动 UDP 处理器
        UdpVideoProcessor proc = new UdpVideoProcessor("127.0.0.1");
        proc.start();
        try {
            Thread.sleep(400);  // 等 socket 绑定

            // 5. 按协议发送所有分片
            DatagramSocket sock = new DatagramSocket();
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            int fragSize = 1400;
            int totalPackets = 0;
            for (int frameId = 0; frameId < frames.size(); frameId++) {
                byte[] frame = frames.get(frameId);
                int offset = 0, fragId = 0;
                while (offset < frame.length) {
                    int len = Math.min(fragSize, frame.length - offset);
                    byte[] chunk = new byte[len];
                    System.arraycopy(frame, offset, chunk, 0, len);

                    ByteBuffer hdr = ByteBuffer.allocate(8);
                    hdr.putShort((short) frameId);
                    hdr.putShort((short) fragId);
                    hdr.putInt(frame.length);
                    byte[] pkt = new byte[8 + len];
                    System.arraycopy(hdr.array(), 0, pkt, 0, 8);
                    System.arraycopy(chunk, 0, pkt, 8, len);

                    sock.send(new DatagramPacket(pkt, pkt.length, addr, port));
                    offset += len;
                    fragId++;
                    totalPackets++;
                    Thread.sleep(2);   // 节流，避免接收缓冲溢出丢包
                }
                Thread.sleep(5);       // 帧间间隔
            }
            sock.close();

            // 6. 等解码完成
            Thread.sleep(4000);

            System.out.println("[TEST] 发送 " + totalPackets + " 包, "
                    + "received=" + proc.getReceivedPackets()
                    + ", decoded=" + proc.getDecodedFrames());
            assertTrue(proc.getReceivedPackets() == totalPackets,
                    "UDP 丢包: 发送 " + totalPackets + ", 收到 " + proc.getReceivedPackets());
            assertTrue(proc.getDecodedFrames() > 0,
                    "没有解码出任何帧 (received=" + proc.getReceivedPackets() + ")");
        } finally {
            proc.stopProcessor();
        }
    }

    /**
     * 不依赖 HEVC 编码器的收包测试：验证 socket 绑定、UDP 接收、计数路径。
     * 任意机器都能跑。
     */
    @Test
    void receivesAndCountsPackets() throws Exception {
        int port;
        try (DatagramSocket s = new DatagramSocket(0)) {
            port = s.getLocalPort();
        }
        Path cfg = Files.createTempFile("mqtt-udp-recv", ".json");
        Files.writeString(cfg, """
                {
                  "mqtt": { "host": "127.0.0.1", "port": 1883, "topics": ["CustomByteBlock"] },
                  "udp": { "host": "127.0.0.1", "port": %d,
                           "recvBufferSize": 65536, "frameTimeoutMs": 5000, "soTimeoutMs": 500 },
                  "video": { "width": 300, "height": 300, "displayScale": 0.5 },
                  "buffer": { "streamBufferSoftLimit": 5120, "streamBufferHardLimit": 500000,
                              "queueBacklogDrop": 5, "decoderThreadCount": 8, "renderIntervalMs": 2 }
                }
                """.formatted(port));
        System.setProperty("mqtt.config", cfg.toString());
        Constants.reload();

        UdpVideoProcessor proc = new UdpVideoProcessor("127.0.0.1");
        proc.start();
        try {
            Thread.sleep(400);
            DatagramSocket sock = new DatagramSocket();
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            int total = 10;
            for (int i = 0; i < total; i++) {
                // 非完整帧（8 字节头 + 少量数据），只验证收包计数
                byte[] pkt = new byte[8 + 6];
                ByteBuffer hdr = ByteBuffer.allocate(8);
                hdr.putShort((short) i);
                hdr.putShort((short) 0);
                hdr.putInt(1000);   // 声称帧共 1000 字节，实际发 6，永不收齐
                System.arraycopy(hdr.array(), 0, pkt, 0, 8);
                sock.send(new DatagramPacket(pkt, pkt.length, addr, port));
            }
            sock.close();
            Thread.sleep(600);

            System.out.println("[TEST] 收包 received=" + proc.getReceivedPackets());
            assertEquals((long) total, proc.getReceivedPackets(),
                    "UDP 收到包数应与发送一致");
        } finally {
            proc.stopProcessor();
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────

    /** 用 JavaCV 编码一段 300x300 HEVC 测试流（Annex-B 原始流）。 */
    private byte[] encodeTestHevc() throws Exception {
        int w = 300, h = 300;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(bos, w, h);
        rec.setFormat("hevc");                                    // 原始 Annex-B HEVC
        rec.setVideoCodec(avcodec.AV_CODEC_ID_HEVC);
        rec.setVideoCodecName("libx265");                         // 强制软件编码器，避免 Windows hevc_mf
        rec.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
        rec.setFrameRate(10);
        rec.setGopSize(1);                                        // 每帧关键帧
        rec.setVideoQuality(10);
        rec.start();
        try (org.bytedeco.javacv.Java2DFrameConverter converter =
                     new org.bytedeco.javacv.Java2DFrameConverter()) {
            for (int i = 0; i < 25; i++) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(new java.awt.Color((i * 10) % 256, 128, (255 - i * 10) % 256));
                g.fillRect(0, 0, w, h);
                g.dispose();
                rec.record(converter.convert(img));
            }
        } finally {
            rec.stop();
            rec.close();
        }
        return bos.toByteArray();
    }

    /** 按 Annex-B 起始码切帧。 */
    private List<byte[]> splitFrames(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        int pos = 0;
        int start;
        while ((start = findStartCode(data, pos)) >= 0) {
            int next = findStartCode(data, start + 4);
            int end = (next >= 0) ? next : data.length;
            byte[] frame = new byte[end - start];
            System.arraycopy(data, start, frame, 0, frame.length);
            frames.add(frame);
            if (next < 0) break;
            pos = next;
        }
        return frames;
    }

    private int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i < data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }
}
