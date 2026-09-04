package com.mqttclient.video;

import com.mqttclient.ext.StatsListener;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Common interface for video stream processors.
 *
 * <p>Unifies the decode pipelines for MQTT/H.264 and UDP/HEVC video sources,
 * letting {@code MainWindow} switch video sources through a single reference.
 */
public interface VideoStreamProcessor {

    /** Starts the processing thread. */
    void start();

    /** Stops the processing thread and releases decoder resources. */
    void stopProcessor();

    /** Takes the latest decoded frame (called by the render timer). */
    BufferedImage takeLatestFrameForRender();

    /** Seq / frame id of the latest frame. */
    int getLatestSeq();

    // ── Statistics ──
    long getReceivedPackets();
    long getDecodedFrames();
    long getLostPackets();

    // ── Callbacks ──
    void setStatusListener(Consumer<String> listener);
    void setStatsListener(StatsListener listener);

    /** Video source label, e.g. "MQTT" or "UDP". */
    default String getSourceLabel() {
        return getClass().getSimpleName();
    }
}
