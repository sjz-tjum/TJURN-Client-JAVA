package com.mqttclient.ext;

/**
 * 原始包处理钩子 —— 在每个视频包完成结构体解析后触发。
 *
 * <p>扩展点：可插入自定义解析逻辑，例如分片重组
 * （对应 Python 版未启用的 video/packet_reassembler.py）、
 * 时间戳统计、抓包落盘等。多个钩子可按注册顺序链式调用。
 */
@FunctionalInterface
public interface PacketProcessor {

    /**
     * @param seqId     包序号 (uint16, 0-65535)
     * @param timestamp 时间戳 (微秒)
     * @param h264Chunk 该包携带的 290 字节 H.264 分片
     */
    void onPacket(int seqId, long timestamp, byte[] h264Chunk);
}
