package com.mqttclient.ext;

/**
 * Statistics callback interface - fired once per second.
 *
 * <p>Mirrors the stats_updated signal in the Python version's processor_thread.py.
 * Extension point for performance monitoring, metric reporting, etc.
 */
@FunctionalInterface
public interface StatsListener {

    /**
     * @param packets     cumulative received packets
     * @param frames      cumulative decoded frames
     * @param decodeFps   decoding FPS
     * @param displayFps  display FPS
     * @param lostPackets cumulative lost packets
     */
    void onStats(long packets, long frames, double decodeFps, double displayFps, long lostPackets);
}
