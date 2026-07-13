package com.mqttclient.ext;

/**
 * 统计回调接口 —— 每秒触发一次。
 *
 * <p>对应 Python 版 processor_thread.py 的 stats_updated 信号。
 * 扩展点：可挂接性能监控、指标上报等。
 */
@FunctionalInterface
public interface StatsListener {

    /**
     * @param packets     累计接收包数
     * @param frames      累计解码帧数
     * @param decodeFps   解码 FPS
     * @param displayFps  显示 FPS
     * @param lostPackets 累计丢包数
     */
    void onStats(long packets, long frames, double decodeFps, double displayFps, long lostPackets);
}
