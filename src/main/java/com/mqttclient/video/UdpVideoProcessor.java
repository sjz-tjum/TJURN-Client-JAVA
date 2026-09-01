package com.mqttclient.video;

import com.mqttclient.config.Constants;
import com.mqttclient.ext.FrameListener;
import com.mqttclient.ext.PacketProcessor;
import com.mqttclient.ext.StatsListener;

import java.awt.image.BufferedImage;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * UDP HEVC 视频流接收处理器。
 *
 * <p>监听 UDP 端口接收 HEVC 分片包，按帧重组后解码显示。
 *
 * <p>UDP 包格式 (big-endian):
 * <pre>
 *   Bytes 0-1: frame_id  (uint16) — 帧编号
 *   Bytes 2-3: frag_id   (uint16) — 帧内分片序号
 *   Bytes 4-7: total_len (uint32) — 当前帧总字节数
 *   Bytes 8+:  HEVC 码流数据
 * </pre>
 */
public class UdpVideoProcessor extends Thread implements VideoStreamProcessor {

    private final String host;
    private final VideoDecoder decoder;
    private final StreamBuffer streamBuffer = new StreamBuffer();

    private volatile boolean running = true;
    private volatile boolean paramSetsCached;

    // 缓存的 VPS/SPS/PPS（Annex-B 起始码前缀的裸 NAL 单元）
    private byte[] cachedVps;
    private byte[] cachedSps;
    private byte[] cachedPps;

    // 帧重组器（线程安全，分片缓存 + 按序拼接）
    private final UdpFrameReassembler reassembler;

    // 接收线程 → 解码线程 的帧队列（解耦，避免解码阻塞接收导致 UDP 缓冲溢出丢包）
    private final ArrayBlockingQueue<FrameData> decodeQueue = new ArrayBlockingQueue<>(60);
    private Thread decodeThread;

    // 最新帧
    private final Object frameLock = new Object();
    private BufferedImage latestFrame;
    private int latestSeq;

    // 统计
    private volatile long receivedPackets;
    private volatile long decodedFrames;
    private volatile long lostFragments;

    // 扩展点
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<PacketProcessor> packetProcessors = new CopyOnWriteArrayList<>();
    private StatsListener statsListener;
    private Consumer<String> statusListener;

    /** @param host 绑定 IP；视频分辨率不受限，按流本身尺寸输出。 */
    public UdpVideoProcessor(String host) {
        this(host, new HevcDecoder(0, 0));
    }

    public UdpVideoProcessor(String host, VideoDecoder decoder) {
        super("UdpVideoProcessor");
        this.host = host;
        this.decoder = decoder;
        this.reassembler = new UdpFrameReassembler(Constants.UDP_FRAME_TIMEOUT_MS,
                msg -> emitStatus(msg));
    }

    // ── 扩展点 ──────────────────────────────────────────────────────

    public void addFrameListener(FrameListener l) { frameListeners.add(l); }
    public void addPacketProcessor(PacketProcessor p) { packetProcessors.add(p); }

    @Override
    public void setStatsListener(StatsListener l) { this.statsListener = l; }
    @Override
    public void setStatusListener(Consumer<String> l) { this.statusListener = l; }

    @Override
    public String getSourceLabel() { return "UDP"; }

    // ── 帧访问 ─────────────────────────────────────────────────────

    @Override
    public BufferedImage takeLatestFrameForRender() {
        synchronized (frameLock) {
            return latestFrame;
        }
    }

    @Override
    public int getLatestSeq() { return latestSeq; }

    @Override
    public long getReceivedPackets() { return receivedPackets; }
    @Override
    public long getDecodedFrames() { return decodedFrames; }
    @Override
    public long getLostPackets() { return lostFragments; }

    // ── 生命周期 ─────────────────────────────────────────────────────

    /** 启动：接收线程（本线程）+ 解码线程。 */
    @Override
    public synchronized void start() {
        super.start();
        decodeThread = new Thread(this::decodeLoop, "UdpDecodeThread");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    /** 解码线程主循环：消费完整帧 → 解码。 */
    private void decodeLoop() {
        while (running || !decodeQueue.isEmpty()) {
            FrameData frame;
            try {
                frame = decodeQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (frame != null) {
                decodeFrame(frame);
            }
        }
    }

    // ── 主循环（接收线程）────────────────────────────────────────────

    @Override
    public void run() {
        int port = Constants.UDP_PORT;
        emitStatus("UDP 视频处理器启动，监听 " + host + ":" + port);

        try (DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName(host))) {
            socket.setSoTimeout(Constants.UDP_SO_TIMEOUT_MS);
            socket.setReceiveBufferSize(Constants.UDP_RECV_BUF_SIZE);

            byte[] buf = new byte[Constants.UDP_RECV_BUF_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            emitStatus("UDP 监听中: " + host + ":" + port);

            long lastStatTime = System.currentTimeMillis();
            long lastDecoded = 0;

            while (running) {
                try {
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(buf, 0, data, 0, packet.getLength());
                    processPacket(data);

                } catch (SocketTimeoutException e) {
                    // 正常超时，继续循环
                } catch (Exception e) {
                    emitStatus("UDP 接收异常: " + e.getMessage());
                }

                // 清理超时帧
                cleanupStaleFrames();

                // 统计（每秒）
                long now = System.currentTimeMillis();
                if (now - lastStatTime >= 1000) {
                    if (statsListener != null) {
                        double decodeFps = (decodedFrames - lastDecoded)
                                / ((now - lastStatTime) / 1000.0);
                        statsListener.onStats(receivedPackets, decodedFrames, decodeFps, 0, lostFragments);
                    }
                    lastDecoded = decodedFrames;
                    lastStatTime = now;
                }
            }
        } catch (SocketException e) {
            emitStatus("UDP Socket 错误: " + e.getMessage());
        } catch (Exception e) {
            emitStatus("UDP 处理器异常: " + e.getMessage());
        }
    }

    // ── 包处理 ─────────────────────────────────────────────────────

    /** 解析单个 UDP 包并重组帧。 */
    private void processPacket(byte[] data) {
        if (data.length < 8) {
            return;
        }

        ByteBuffer bb = ByteBuffer.wrap(data);
        int frameId = bb.getShort() & 0xFFFF;
        int fragId = bb.getShort() & 0xFFFF;
        int totalLen = bb.getInt();
        byte[] hevcData = new byte[data.length - 8];
        System.arraycopy(data, 8, hevcData, 0, hevcData.length);

        receivedPackets++;

        // 扩展点钩子
        for (PacketProcessor p : packetProcessors) {
            try { p.onPacket(frameId, 0, hevcData); } catch (Exception ignore) {}
        }

        // 重组帧；返回非 null 表示该帧已收齐
        byte[] frameData = reassembler.addFragment(frameId, fragId, hevcData, totalLen);
        if (frameData != null) {
            onFrameComplete(frameId, frameData);
        }
    }

    /** 完整帧收到后（接收线程）：提取参数集 → 入队给解码线程。 */
    private void onFrameComplete(int frameId, byte[] frameData) {
        // 自动检测并缓存 VPS/SPS/PPS（接收线程做，只扫描不耗时）
        extractParamSets(frameData);

        // 入队给解码线程；队列满时丢最旧帧（低延迟优先）
        if (!decodeQueue.offer(new FrameData(frameId, frameData))) {
            decodeQueue.poll();
            decodeQueue.offer(new FrameData(frameId, frameData));
            lostFragments++;
        }
    }

    /** 解码线程：把缓存参数集 + 帧数据写入流缓冲并解码。 */
    private void decodeFrame(FrameData frame) {
        // 将缓存参数集 + 帧数据写入流缓冲
        if (paramSetsCached) {
            if (cachedVps != null) streamBuffer.extend(cachedVps);
            if (cachedSps != null) streamBuffer.extend(cachedSps);
            if (cachedPps != null) streamBuffer.extend(cachedPps);
        }
        streamBuffer.extend(frame.data);

        // 解码
        List<BufferedImage> images = decoder.parseAndDecode(streamBuffer);
        for (BufferedImage img : images) {
            decodedFrames++;
            synchronized (frameLock) {
                latestFrame = img;
                latestSeq = frame.id;
            }
            for (FrameListener l : frameListeners) {
                try { l.onFrame(img, frame.id); } catch (Exception ignore) {}
            }
        }
    }

    // ── VPS/SPS/PPS 自动提取 ───────────────────────────────────────

    /**
     * 从 HEVC Annex-B 数据中提取 VPS/SPS/PPS（NAL type 32/33/34）并缓存。
     * 只执行一次，后续帧复用缓存。
     */
    private void extractParamSets(byte[] data) {
        if (paramSetsCached) return;

        int offset = 0;
        while (offset < data.length - 4) {
            int start = findStartCode(data, offset);
            if (start < 0) break;

            int nalLen = (data[start + 2] == 1) ? 3 : 4;
            int headerPos = start + nalLen;
            if (headerPos + 1 >= data.length) break;

            // HEVC NAL header 是 2 字节
            int nalType = (data[headerPos] & 0x7E) >> 1;  // 跳过 forbidden bit，取 6 位

            int nextStart = findStartCode(data, headerPos + 1);
            int nalEnd = (nextStart >= 0) ? nextStart : data.length;
            byte[] nalUnit = new byte[nalEnd - start];
            System.arraycopy(data, start, nalUnit, 0, nalUnit.length);

            switch (nalType) {
                case 32 -> cachedVps = nalUnit; // VPS
                case 33 -> cachedSps = nalUnit; // SPS
                case 34 -> cachedPps = nalUnit; // PPS
            }

            if (cachedVps != null && cachedSps != null && cachedPps != null) {
                paramSetsCached = true;
                emitStatus("已从流内提取并缓存 VPS/SPS/PPS");
                // 注入为解码器 extradata (Annex-B)，避免依赖 in-band 参数顺序
                try {
                    byte[] annexb = new byte[cachedVps.length + cachedSps.length + cachedPps.length];
                    System.arraycopy(cachedVps, 0, annexb, 0, cachedVps.length);
                    System.arraycopy(cachedSps, 0, annexb, cachedVps.length, cachedSps.length);
                    System.arraycopy(cachedPps, 0, annexb,
                            cachedVps.length + cachedSps.length, cachedPps.length);
                    decoder.setExtradata(annexb);
                } catch (Exception e) {
                    emitStatus("注入 extradata 失败: " + e.getMessage());
                }
                return;
            }
            offset = nalEnd;
        }
    }

    private static int findStartCode(byte[] data, int start) {
        for (int i = Math.max(0, start); i < data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;          // 3-byte start code
                if (data[i + 2] == 0 && data[i + 3] == 1) return i; // 4-byte
            }
        }
        return -1;
    }

    // ── 超时清理 ───────────────────────────────────────────────────

    private void cleanupStaleFrames() {
        long now = System.currentTimeMillis();
        lostFragments += reassembler.cleanupStale(now);
    }

    // ── 生命周期 ───────────────────────────────────────────────────

    @Override
    public void stopProcessor() {
        running = false;
        interrupt();
        if (decodeThread != null) {
            decodeThread.interrupt();
        }
        try { join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (decodeThread != null) {
            try { decodeThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        decoder.close();
        reassembler.clear();
        decodeQueue.clear();
        emitStatus("UDP 视频处理器已停止");
    }

    private void emitStatus(String msg) {
        System.out.println("[UDP] " + msg);
        if (statusListener != null) {
            statusListener.accept(msg);
        }
    }

    /** 一帧数据（帧号 + Annex-B 字节），由接收线程入队、解码线程消费。 */
    private record FrameData(int id, byte[] data) {
    }
}
