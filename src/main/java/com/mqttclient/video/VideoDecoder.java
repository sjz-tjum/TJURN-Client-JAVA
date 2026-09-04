package com.mqttclient.video;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Abstract video decoder interface - reserved extension point.
 *
 * <p>{@link H264Decoder} is the default JavaCV/FFmpeg-based implementation. Other
 * implementations (hardware decoding, other codecs, etc.) can be added later without
 * changing {@link VideoProcessor}.
 */
public interface VideoDecoder {

    /**
     * Injects an avcC-format parameter set (SPS/PPS).
     * Corresponds to H264Decoder.set_extradata in the Python version.
     */
    void setExtradata(byte[] avccBytes);

    /**
     * Decodes Annex-B H.264 data from the buffer.
     * Data successfully consumed is removed from the buffer.
     *
     * @param buffer accumulated H.264 stream buffer (modified in place)
     * @return list of images decoded in this call (may be empty)
     */
    List<BufferedImage> parseAndDecode(StreamBuffer buffer);

    /** Forcefully resets the decoder (keeps the cached extradata). */
    void reset();

    /** Releases underlying resources. */
    void close();
}
