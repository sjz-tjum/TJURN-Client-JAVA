package com.mqttclient.video;

import com.mqttclient.config.Constants;
import com.mqttclient.ext.FrameListener;
import com.mqttclient.ext.PacketProcessor;
import com.mqttclient.ext.StatsListener;
import com.mqttclient.mqtt.MqttReceiver;
import com.mqttclient.protobuf.VideoPacketParser;
import com.mqttclient.protobuf.VideoPacketParser.VideoPacket;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Video processing thread.
 *
 * <p>Corresponds to VideoProcessorThread in the Python version (video/processor_thread.py):
 * <ul>
 *   <li>Consumes the MQTT message queue (drops stale packets when backed up)</li>
 *   <li>Protobuf decoding + struct parsing + packet-loss detection</li>
 *   <li>Extracts SPS/PPS to build avcC and injects it into the decoder (attempted on every packet until successful)</li>
 *   <li>Accumulates the stream buffer; truncates to the latest IDR when the soft limit is exceeded</li>
 *   <li>Decodes and delivers the latest frame via {@link FrameListener}</li>
 *   <li>Reports stats once per second via {@link StatsListener}</li>
 * </ul>
 */
public class VideoProcessor extends Thread implements VideoStreamProcessor {

    private final MqttReceiver mqtt;
    private final VideoDecoder decoder;
    private final BlockingQueue<byte[]> queue;

    private volatile boolean running = true;

    private final StreamBuffer streamBuffer = new StreamBuffer();
    private Integer lastSeq = null;
    private boolean extradataExtracted = false;

    // ==== Latest frame (consumed by the render timer) ====
    private final Object frameLock = new Object();
    private BufferedImage latestFrame = null;
    private int latestSeq = 0;

    // ==== Statistics ====
    private volatile long receivedPackets = 0;
    private volatile long decodedFrames = 0;
    private volatile long lostPackets = 0;
    private long lastStatTime = System.currentTimeMillis();
    private long fpsCounter = 0;
    private long displayFpsCounter = 0;
    private long lastDisplayStatTime = System.currentTimeMillis();

    // ==== Extension points ====
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<PacketProcessor> packetProcessors = new CopyOnWriteArrayList<>();
    private StatsListener statsListener;
    private Consumer<String> statusListener;

    public VideoProcessor(MqttReceiver mqtt) {
        this(mqtt, new H264Decoder(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT));
    }

    /** Injectable custom decoder. */
    public VideoProcessor(MqttReceiver mqtt, VideoDecoder decoder) {
        super("VideoProcessor");
        this.mqtt = mqtt;
        this.decoder = decoder;
        this.queue = mqtt.getMessageQueue();
    }

    // ==== Extension point registration ====
    public void addFrameListener(FrameListener l) {
        frameListeners.add(l);
    }

    public void addPacketProcessor(PacketProcessor p) {
        packetProcessors.add(p);
    }

    @Override
    public void setStatsListener(StatsListener l) {
        this.statsListener = l;
    }

    @Override
    public String getSourceLabel() {
        return "MQTT";
    }

    public void setStatusListener(Consumer<String> l) {
        this.statusListener = l;
    }

    /** Returns the latest frame (called by the render timer) and accumulates the display FPS counter. */
    public BufferedImage takeLatestFrameForRender() {
        synchronized (frameLock) {
            if (latestFrame != null) {
                displayFpsCounter++;
            }
            return latestFrame;
        }
    }

    public int getLatestSeq() {
        return latestSeq;
    }

    @Override
    public void run() {
        emitStatus("视频处理线程启动");
        while (running) {
            try {
                // Drop stale packets when the queue is backed up (corresponds to qsize() > 5)
                while (queue.size() > Constants.QUEUE_BACKLOG_DROP) {
                    queue.poll();
                }
                byte[] payload = queue.poll(10, TimeUnit.MILLISECONDS);
                if (payload != null) {
                    processMessage(payload);
                }
                updateStats();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                emitStatus("处理消息异常: " + e.getMessage());
            }
        }
    }

    private void processMessage(byte[] payload) {
        VideoPacket packet;
        try {
            packet = VideoPacketParser.parse(payload);
        } catch (Exception e) {
            emitStatus("protobuf 解析失败: " + e.getMessage());
            return;
        }
        if (packet == null) {
            emitStatus("包长度异常，跳过");
            return;
        }

        int seqId = packet.seqId();
        receivedPackets++;

        // Packet-loss detection (records only; does not clear or reset)
        if (lastSeq != null) {
            int expected = (lastSeq + 1) & 0xFFFF;
            if (seqId != expected) {
                lostPackets++;
                emitStatus("丢包: 期望 " + expected + ", 收到 " + seqId);
            }
        }
        lastSeq = seqId;

        // Extension point: raw packet hook chain
        for (PacketProcessor p : packetProcessors) {
            try {
                p.onPacket(seqId, packet.timestamp(), packet.h264Chunk());
            } catch (Exception ignore) {
                // Hook exceptions must not affect the main flow
            }
        }

        // Detect SPS/PPS: only flag it; do not inject avcC extradata.
        // Note: Annex-B slices initialize the decoder from in-band parameter sets; injecting
        // avcC switches FFmpeg to AVCC mode (length-prefixed NAL splitting), which does not
        // match Annex-B start-code slices and breaks splitting.
        if (!extradataExtracted) {
            byte[] avcc = AvccExtractor.extractAvcc(packet.h264Chunk());
            if (avcc != null) {
                extradataExtracted = true;
                emitStatus("已从 seq=" + seqId + " 检测到 SPS/PPS (流内解码)");
            }
        }

        streamBuffer.extend(packet.h264Chunk());

        // Buffer soft limit: truncate to the latest IDR
        if (streamBuffer.size() > Constants.STREAM_BUFFER_SOFT_LIMIT) {
            truncateToLastIdr();
        }

        // Decode
        List<BufferedImage> images = decoder.parseAndDecode(streamBuffer);
        for (BufferedImage img : images) {
            decodedFrames++;
            fpsCounter++;
            synchronized (frameLock) {
                latestFrame = img;   // keep only the latest frame
                latestSeq = seqId;
            }
            // Extension point: frame callbacks
            for (FrameListener l : frameListeners) {
                try {
                    l.onFrame(img, seqId);
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** Scans backward for the last IDR (NAL type 5) start code and drops the data before it. Matches the original logic. */
    private void truncateToLastIdr() {
        int idrPos = -1;
        for (int i = streamBuffer.size() - 5; i > 0; i--) {
            boolean is4 = streamBuffer.get(i) == 0 && streamBuffer.get(i + 1) == 0
                    && streamBuffer.get(i + 2) == 0 && streamBuffer.get(i + 3) == 1;
            boolean is3 = streamBuffer.get(i) == 0 && streamBuffer.get(i + 1) == 0
                    && streamBuffer.get(i + 2) == 1;
            if (is4 || is3) {
                int nalStart = i + (is4 ? 4 : 3);
                if (nalStart < streamBuffer.size() && (streamBuffer.get(nalStart) & 0x1F) == 5) {
                    idrPos = i;
                    break;
                }
            }
        }
        if (idrPos >= 0) {
            streamBuffer.deleteFront(idrPos);
            emitStatus("缓冲区截断至最新 IDR (丢弃 " + idrPos + " 字节)");
        } else {
            streamBuffer.clear();
            emitStatus("缓冲区过大且无 IDR，清空");
        }
    }

    /** Emits statistics once per second. Corresponds to _update_stats. */
    private void updateStats() {
        long now = System.currentTimeMillis();
        double elapsed = (now - lastStatTime) / 1000.0;
        if (elapsed >= 1.0) {
            double decodeFps = fpsCounter / elapsed;
            double elapsedDisplay = (now - lastDisplayStatTime) / 1000.0;
            double displayFps = elapsedDisplay > 0 ? displayFpsCounter / elapsedDisplay : 0.0;

            if (statsListener != null) {
                statsListener.onStats(receivedPackets, decodedFrames, decodeFps, displayFps, lostPackets);
            }

            displayFpsCounter = 0;
            lastDisplayStatTime = now;
            fpsCounter = 0;
            lastStatTime = now;
        }
    }

    /** Stops the thread and releases the decoder. Corresponds to stop(). */
    public void stopProcessor() {
        running = false;
        interrupt();
        try {
            join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        decoder.close();
    }

    private void emitStatus(String msg) {
        if (statusListener != null) {
            statusListener.accept(msg);
        }
    }

    // ==== Read-only statistics access (fallback for the UI) ====
    public long getReceivedPackets() {
        return receivedPackets;
    }

    public long getDecodedFrames() {
        return decodedFrames;
    }

    public long getLostPackets() {
        return lostPackets;
    }
}
