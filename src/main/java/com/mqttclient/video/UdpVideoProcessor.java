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
 * UDP HEVC video stream receiver processor.
 *
 * <p>Listens on a UDP port, receives HEVC fragment packets, reassembles them into
 * frames, then decodes and displays them.
 *
 * <p>UDP packet format (big-endian):
 * <pre>
 *   Bytes 0-1: frame_id  (uint16) — frame number
 *   Bytes 2-3: frag_id   (uint16) — fragment index within the frame
 *   Bytes 4-7: total_len (uint32) — total byte length of the current frame
 *   Bytes 8+:  HEVC stream data
 * </pre>
 */
public class UdpVideoProcessor extends Thread implements VideoStreamProcessor {

    private final String host;
    private final VideoDecoder decoder;
    private final StreamBuffer streamBuffer = new StreamBuffer();

    private volatile boolean running = true;
    private volatile boolean paramSetsCached;

    // Cached VPS/SPS/PPS (raw NAL units prefixed with Annex-B start codes)
    private byte[] cachedVps;
    private byte[] cachedSps;
    private byte[] cachedPps;

    // Frame reassembler (thread-safe; caches fragments and concatenates them in order)
    private final UdpFrameReassembler reassembler;

    // Frame queue from the receive thread to the decode thread (decouples the two so decoding cannot block reception and cause UDP buffer-overflow loss)
    private final ArrayBlockingQueue<FrameData> decodeQueue = new ArrayBlockingQueue<>(60);
    private Thread decodeThread;

    // Latest frame
    private final Object frameLock = new Object();
    private BufferedImage latestFrame;
    private int latestSeq;

    // Statistics
    private volatile long receivedPackets;
    private volatile long decodedFrames;
    private volatile long lostFragments;

    // Extension points
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<PacketProcessor> packetProcessors = new CopyOnWriteArrayList<>();
    private StatsListener statsListener;
    private Consumer<String> statusListener;

    /** @param host the bind IP; video resolution is not limited — output follows the stream size. */
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

    // ── Extension points ──────────────────────────────────────────────────────

    public void addFrameListener(FrameListener l) { frameListeners.add(l); }
    public void addPacketProcessor(PacketProcessor p) { packetProcessors.add(p); }

    @Override
    public void setStatsListener(StatsListener l) { this.statsListener = l; }
    @Override
    public void setStatusListener(Consumer<String> l) { this.statusListener = l; }

    @Override
    public String getSourceLabel() { return "UDP"; }

    // ── Frame access ─────────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────

    /** Starts the receive thread (this thread) and the decode thread. */
    @Override
    public synchronized void start() {
        super.start();
        decodeThread = new Thread(this::decodeLoop, "UdpDecodeThread");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    /** Decode thread main loop: consume complete frames and decode them. */
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

    // ── Main loop (receive thread) ────────────────────────────────────────────

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
                    // Normal timeout; keep looping
                } catch (Exception e) {
                    emitStatus("UDP 接收异常: " + e.getMessage());
                }

                // Clean up timed-out frames
                cleanupStaleFrames();

                // Statistics (per second)
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

    // ── Packet handling ─────────────────────────────────────────────────────

    /** Parses a single UDP packet and reassembles it into a frame. */
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

        // Extension point hooks
        for (PacketProcessor p : packetProcessors) {
            try { p.onPacket(frameId, 0, hevcData); } catch (Exception ignore) {}
        }

        // Reassemble the frame; a non-null return means the frame is complete
        byte[] frameData = reassembler.addFragment(frameId, fragId, hevcData, totalLen);
        if (frameData != null) {
            onFrameComplete(frameId, frameData);
        }
    }

    /** After a complete frame arrives (receive thread): extract parameter sets, then enqueue it for the decode thread. */
    private void onFrameComplete(int frameId, byte[] frameData) {
        // Auto-detect and cache VPS/SPS/PPS (done on the receive thread; scan only, no heavy work)
        extractParamSets(frameData);

        // Enqueue for the decode thread; when the queue is full, drop the oldest frame (low latency first)
        if (!decodeQueue.offer(new FrameData(frameId, frameData))) {
            decodeQueue.poll();
            decodeQueue.offer(new FrameData(frameId, frameData));
            lostFragments++;
        }
    }

    /** Decode thread: writes the cached parameter sets plus frame data into the stream buffer and decodes. */
    private void decodeFrame(FrameData frame) {
        // Write the cached parameter sets plus frame data into the stream buffer
        if (paramSetsCached) {
            if (cachedVps != null) streamBuffer.extend(cachedVps);
            if (cachedSps != null) streamBuffer.extend(cachedSps);
            if (cachedPps != null) streamBuffer.extend(cachedPps);
        }
        streamBuffer.extend(frame.data);

        // Decode
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

    // ── VPS/SPS/PPS auto extraction ───────────────────────────────────────

    /**
     * Extracts and caches VPS/SPS/PPS (NAL types 32/33/34) from HEVC Annex-B data.
     * Runs only once; later frames reuse the cached parameter sets.
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

            // The HEVC NAL header is 2 bytes
            int nalType = (data[headerPos] & 0x7E) >> 1;  // skip the forbidden bit; take the next 6 bits

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
                // Inject as decoder extradata (Annex-B) to avoid depending on in-band parameter order
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

    // ── Timeout cleanup ───────────────────────────────────────────────────

    private void cleanupStaleFrames() {
        long now = System.currentTimeMillis();
        lostFragments += reassembler.cleanupStale(now);
    }

    // ── Lifecycle ───────────────────────────────────────────────────

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

    /** A frame of data (frame id + Annex-B bytes), enqueued by the receive thread and consumed by the decode thread. */
    private record FrameData(int id, byte[] data) {
    }
}
