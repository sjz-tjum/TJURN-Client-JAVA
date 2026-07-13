package com.mqttclient.ext;

import java.awt.image.BufferedImage;

/**
 * 帧回调接口 —— 每解码出一帧后触发。
 *
 * <p>扩展点：可挂接录制、目标检测、叠加绘制（对应 Python 版 utils/overlay.py 的
 * 十字准星绘制）等自定义功能。UI 层实现此接口以更新显示。
 */
@FunctionalInterface
public interface FrameListener {

    /**
     * @param frame  解码得到的一帧 (BGR/RGB 已转为 BufferedImage)
     * @param seqId  该帧对应的最新包序号
     */
    void onFrame(BufferedImage frame, int seqId);
}
