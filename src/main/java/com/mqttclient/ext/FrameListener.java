package com.mqttclient.ext;

import java.awt.image.BufferedImage;

/**
 * Frame callback interface - fired each time a frame is decoded.
 *
 * <p>Extension point for custom functionality such as recording, object detection, and overlay
 * drawing (mirrors the crosshair rendering in the Python version's utils/overlay.py). The UI layer
 * implements this interface to refresh the display.
 */
@FunctionalInterface
public interface FrameListener {

    /**
     * @param frame decoded frame (BGR/RGB already converted to a BufferedImage)
     * @param seqId sequence ID of the newest packet for this frame
     */
    void onFrame(BufferedImage frame, int seqId);
}
