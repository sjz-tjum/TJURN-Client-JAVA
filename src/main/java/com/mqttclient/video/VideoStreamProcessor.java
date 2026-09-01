package com.mqttclient.video;

import com.mqttclient.ext.StatsListener;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 视频流处理器公共接口。
 *
 * <p>统一 MQTT/H.264 和 UDP/HEVC 两种视频源的解码管线，
 * 供 {@code MainWindow} 通过一个引用切换视频源。
 */
public interface VideoStreamProcessor {

    /** 启动处理线程。 */
    void start();

    /** 停止处理线程并释放解码器资源。 */
    void stopProcessor();

    /** 取最新解码帧（渲染定时器调用）。 */
    BufferedImage takeLatestFrameForRender();

    /** 最新帧的 seq / frame id。 */
    int getLatestSeq();

    // ── 统计 ──
    long getReceivedPackets();
    long getDecodedFrames();
    long getLostPackets();

    // ── 回调 ──
    void setStatusListener(Consumer<String> listener);
    void setStatsListener(StatsListener listener);

    /** 视频源标识，如 "MQTT" 或 "UDP"。 */
    default String getSourceLabel() {
        return getClass().getSimpleName();
    }
}
