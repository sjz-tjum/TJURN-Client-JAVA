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
 * 视频处理线程。
 *
 * <p>对应 Python 版 video/processor_thread.py 的 VideoProcessorThread：
 * <ul>
 *   <li>消费 MQTT 消息队列（积压时丢弃旧包）</li>
 *   <li>protobuf 解包 + 结构体解析 + 丢包检测</li>
 *   <li>提取 SPS/PPS 构建 avcC 并注入解码器（未成功前每包都尝试）</li>
 *   <li>累积流缓冲，超过软上限时截断到最新 IDR</li>
 *   <li>解码并通过 {@link FrameListener} 回调最新帧</li>
 *   <li>每秒通过 {@link StatsListener} 回调统计</li>
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

    // ==== 最新帧（供渲染定时器取用）====
    private final Object frameLock = new Object();
    private BufferedImage latestFrame = null;
    private int latestSeq = 0;

    // ==== 统计 ====
    private volatile long receivedPackets = 0;
    private volatile long decodedFrames = 0;
    private volatile long lostPackets = 0;
    private long lastStatTime = System.currentTimeMillis();
    private long fpsCounter = 0;
    private long displayFpsCounter = 0;
    private long lastDisplayStatTime = System.currentTimeMillis();

    // ==== 扩展点 ====
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();
    private final List<PacketProcessor> packetProcessors = new CopyOnWriteArrayList<>();
    private StatsListener statsListener;
    private Consumer<String> statusListener;

    public VideoProcessor(MqttReceiver mqtt) {
        this(mqtt, new H264Decoder(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT));
    }

    /** 可注入自定义解码器*/
    public VideoProcessor(MqttReceiver mqtt, VideoDecoder decoder) {
        super("VideoProcessor");
        this.mqtt = mqtt;
        this.decoder = decoder;
        this.queue = mqtt.getMessageQueue();
    }

    // ==== 扩展点注册 ====
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

    /** 取最新帧（渲染定时器调用），并累加显示 FPS 计数。 */
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
                // 队列积压时丢弃旧包（对应 qsize() > 5）
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

        // 丢包检测（只记录，不做清空/重置）
        if (lastSeq != null) {
            int expected = (lastSeq + 1) & 0xFFFF;
            if (seqId != expected) {
                lostPackets++;
                emitStatus("丢包: 期望 " + expected + ", 收到 " + seqId);
            }
        }
        lastSeq = seqId;

        // 扩展点：原始包钩子链
        for (PacketProcessor p : packetProcessors) {
            try {
                p.onPacket(seqId, packet.timestamp(), packet.h264Chunk());
            } catch (Exception ignore) {
                // 钩子异常不影响主流程
            }
        }

        // 检测 SPS/PPS：只标记，不注入 avcC extradata。
        // 注：Annex-B 分片用流内参数集即可初始化解码器；注入 avcC 会让 FFmpeg 切到
        // AVCC 模式（按长度前缀拆 NAL），与 Annex-B 起始码分片不匹配导致拆分失败。
        if (!extradataExtracted) {
            byte[] avcc = AvccExtractor.extractAvcc(packet.h264Chunk());
            if (avcc != null) {
                extradataExtracted = true;
                emitStatus("已从 seq=" + seqId + " 检测到 SPS/PPS (流内解码)");
            }
        }

        streamBuffer.extend(packet.h264Chunk());

        // 缓冲区软上限：截断到最新 IDR
        if (streamBuffer.size() > Constants.STREAM_BUFFER_SOFT_LIMIT) {
            truncateToLastIdr();
        }

        // 解码
        List<BufferedImage> images = decoder.parseAndDecode(streamBuffer);
        for (BufferedImage img : images) {
            decodedFrames++;
            fpsCounter++;
            synchronized (frameLock) {
                latestFrame = img;   // 只保留最新一帧
                latestSeq = seqId;
            }
            // 扩展点：帧回调
            for (FrameListener l : frameListeners) {
                try {
                    l.onFrame(img, seqId);
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** 从后往前找最后一个 IDR(NAL type 5) 起始码，截断其之前的数据。对应原版逻辑。 */
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

    /** 每 1 秒发一次统计。对应 _update_stats。 */
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

    /** 停止线程并释放解码器。对应 stop()。 */
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

    // ==== 统计只读访问（供 UI 兜底） ====
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
