package com.mqttclient.video;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 视频解码器抽象接口 —— 预留扩展点。
 *
 * <p>{@link H264Decoder} 是基于 JavaCV/FFmpeg 的默认实现。日后可新增其他实现
 * （硬件解码、其他编码格式等）而无需改动 {@link VideoProcessor}。
 */
public interface VideoDecoder {

    /**
     * 注入 avcC 格式的参数集 (SPS/PPS)。
     * 对应 Python 版 H264Decoder.set_extradata。
     */
    void setExtradata(byte[] avccBytes);

    /**
     * 解码缓冲区中的 Annex-B H.264 数据。
     * 已成功消费的数据会从 buffer 中移除。
     *
     * @param buffer 累积的 H.264 流缓冲（原地修改）
     * @return 本次解码得到的图像列表（可能为空）
     */
    List<BufferedImage> parseAndDecode(StreamBuffer buffer);

    /** 强制重置解码器（保留已缓存的 extradata）。 */
    void reset();

    /** 释放底层资源。 */
    void close();
}
