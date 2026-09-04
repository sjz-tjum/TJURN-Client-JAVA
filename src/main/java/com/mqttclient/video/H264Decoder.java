package com.mqttclient.video;

import com.mqttclient.config.Constants;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN;

/**
 * JavaCV / FFmpeg-based H.264 decoder.
 *
 * <p>Corresponds to the Python video/h264_decoder.py (PyAV implementation):
 * <ul>
 *   <li>Creates an H.264 decoder context with multithreading
 *       (thread_count=8) and low latency</li>
 *   <li>Supports injecting avcC extradata (SPS/PPS); reuses the cache on reset</li>
 *   <li>Splits complete NALs by Annex-B start codes and decodes them one packet at a time</li>
 *   <li>Outputs BGR24, converted to a 300x300 BufferedImage</li>
 * </ul>
 */
public class H264Decoder implements VideoDecoder {

    private final int width;
    private final int height;

    private AVCodec codec;
    private AVCodecContext codecCtx;
    private AVCodecParserContext parser;
    private SwsContext swsCtx;

    private byte[] cachedExtradata;   // Cache avcC, reused on reset
    private long frameCount = 0;

    // ==== Debug: hex dump of the raw H.264 stream received ====
    private java.io.FileOutputStream dumpStream;

    /**
     * Starts saving the raw Annex-B data received by the decoder to a file for
     * offline analysis. The file can be played or inspected with ffplay / ffprobe.
     */
    public synchronized void startDump(String filePath) {
        try {
            dumpStream = new java.io.FileOutputStream(filePath);
            System.out.println("[Decoder] 开始 dump 原始流到: " + filePath);
        } catch (java.io.IOException e) {
            System.err.println("[Decoder] 无法创建 dump 文件: " + e.getMessage());
        }
    }

    public synchronized void stopDump() {
        if (dumpStream != null) {
            try { dumpStream.close(); } catch (java.io.IOException ignored) {}
            dumpStream = null;
            System.out.println("[Decoder] 停止 dump");
        }
    }

    public H264Decoder(int width, int height) {
        this.width = width;
        this.height = height;
        resetCodec("init");
    }

    /** Holds the BytePointer reference, preventing codecCtx.extradata from becoming a dangling pointer after GC. */
    private BytePointer extradataPtr;

    @Override
    public void setExtradata(byte[] avccBytes) {
        this.cachedExtradata = avccBytes;
        resetCodec("set extradata");
        System.out.println("[Decoder] 已接收外部 extradata 并缓存");
    }

    private synchronized void resetCodec(String reason) {
        closeInternal();
        try {
            codec = avcodec_find_decoder(AV_CODEC_ID_H264);
            if (codec == null || codec.isNull()) {
                System.out.println("[Decoder] 找不到 H264 解码器");
                return;
            }
            codecCtx = avcodec_alloc_context3(codec);
            codecCtx.thread_type(2); // AV_THREAD_FRAME = 2
            codecCtx.thread_count(Constants.DECODER_THREAD_COUNT);
            codecCtx.flags(codecCtx.flags() | AV_CODEC_FLAG_LOW_DELAY);

            // Inject the cached extradata
            if (cachedExtradata != null && cachedExtradata.length > 0) {
                extradataPtr = new BytePointer(cachedExtradata.length + AV_INPUT_BUFFER_PADDING_SIZE);
                extradataPtr.put(cachedExtradata, 0, cachedExtradata.length);
                // Zero the padding area (new BytePointer is already zeroed; explicit zeroing ensures safety)
                for (int i = 0; i < AV_INPUT_BUFFER_PADDING_SIZE; i++) {
                    extradataPtr.put((long) cachedExtradata.length + i, (byte) 0);
                }
                codecCtx.extradata(extradataPtr);
                codecCtx.extradata_size(cachedExtradata.length);
                System.out.println("[Decoder] 已注入缓存的 extradata");
            } else {
                System.out.println("[Decoder] 暂无 extradata，依赖流内参数集");
            }

            if (avcodec_open2(codecCtx, codec, (PointerPointer<?>) null) < 0) {
                System.out.println("[Decoder] 打开解码器失败");
                codecCtx = null;
                return;
            }

            parser = av_parser_init(AV_CODEC_ID_H264);
            System.out.printf("[Decoder] 解码器已重置 (原因: %s)%n", reason);
        } catch (Exception e) {
            System.out.printf("[Decoder] 创建解码器失败: %s%n", e.getMessage());
            codecCtx = null;
        }
    }

    @Override
    public void reset() {
        resetCodec("manual reset");
    }

    /**
     * Extracts all complete NALs (including start codes) from the buffer and
     * returns the number of bytes consumed. Corresponds to _split_complete_nalus:
     * the last incomplete NAL is retained.
     */
    private int findConsumedLength(StreamBuffer buffer) {
        int consumed = 0;
        int i = 0;
        int size = buffer.size();
        while (i < size) {
            int start = buffer.findStartCode(i);
            if (start == -1) {
                break;
            }
            int nextStart = buffer.findStartCode(start + 1);
            if (nextStart == -1) {
                break; // The last NAL may be incomplete; keep it
            }
            consumed = nextStart;
            i = nextStart;
        }
        return consumed;
    }

    @Override
    public synchronized List<BufferedImage> parseAndDecode(StreamBuffer buffer) {
        List<BufferedImage> images = new ArrayList<>();
        if (codecCtx == null || buffer.isEmpty()) {
            return images;
        }

        // Align to the start code
        int startPos = buffer.findStartCode(0);
        if (startPos == -1) {
            if (buffer.size() > Constants.STREAM_BUFFER_HARD_LIMIT) {
                System.out.println("[Decoder] 无起始码且过大，清空");
                buffer.clear();
            }
            return images;
        }
        if (startPos > 0) {
            buffer.deleteFront(startPos);
        }

        int consumed = findConsumedLength(buffer);
        if (consumed <= 0) {
            return images;
        }

        byte[] dataToDecode = buffer.copyRange(0, consumed);
        try {
            decodeAnnexB(dataToDecode, images);
            buffer.deleteFront(consumed);
            // Keep only the latest 5 frames (corresponds to images[-5:])
            while (images.size() > 5) {
                images.remove(0);
            }
        } catch (Exception e) {
            System.out.printf("[Decoder] 解码异常: %s，保留数据等待下一个 IDR%n", e.getMessage());
        }
        return images;
    }

    /** Processes a chunk of Annex-B data using the parser + decoder. */
    private void decodeAnnexB(byte[] data, List<BufferedImage> out) {
        // Debug: save raw data to file
        if (dumpStream != null) {
            try {
                dumpStream.write(data);
                dumpStream.flush();
            } catch (java.io.IOException ignored) {}
        }

        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();

        try {
            // av_new_packet allocates internally with av_malloc; av_packet_free frees with av_free.
            // The allocator is consistent, so there is no heap corruption from mixing malloc/av_free.
            int paddedSize = data.length + AV_INPUT_BUFFER_PADDING_SIZE;
            if (av_new_packet(pkt, paddedSize) < 0) {
                System.err.println("[Decoder] av_new_packet 分配失败");
                return;
            }
            // Copy data (av_new_packet already zeroes the buffer, so padding is automatically 0)
            pkt.data().put(data, 0, data.length);
            pkt.size(data.length);

            decodePacket(pkt, frame, out);
        } catch (Exception e) {
            System.err.println("[Decoder] 解码异常: " + e.getMessage());
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);   // Safely free the av_malloc memory
        }
    }
    private void decodePacket(AVPacket pkt, AVFrame frame, List<BufferedImage> out) {
        int ret = avcodec_send_packet(codecCtx, pkt);
        if (ret < 0) {
            System.err.println("[Decoder] avcodec_send_packet 失败, err=" + ret);
            return;
        }
        while (ret >= 0) {
            ret = avcodec_receive_frame(codecCtx, frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) {
                break;
            }
            if (ret < 0) {
                System.err.println("[Decoder] avcodec_receive_frame 失败, err=" + ret);
                break;
            }
            BufferedImage img = frameToImage(frame);
            if (img != null) {
                out.add(img);
                frameCount++;
            }
        }
    }

    private AVFrame bgrFrame;
    private BytePointer bgrBuffer;
    private int bgrBufferSize;
    private int lastOutW = -1;
    private int lastOutH = -1;

    /**
     * Converts an AVFrame (YUV) to BGR24 -> BufferedImage.
     * If the width/height given to the constructor is > 0, scales to that fixed
     * size; if 0 (UDP mode), outputs at the stream's native resolution.
     */
    private BufferedImage frameToImage(AVFrame frame) {
        int srcW = frame.width();
        int srcH = frame.height();
        if (srcW <= 0 || srcH <= 0) {
            return null;
        }
        int outW = (width > 0) ? width : srcW;
        int outH = (height > 0) ? height : srcH;

        swsCtx = sws_getCachedContext(swsCtx,
                srcW, srcH, codecCtx.pix_fmt(),
                outW, outH, AV_PIX_FMT_BGR24,
                SWS_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null) {
            return null;
        }

        // Reuse bgrFrame + bgrBuffer to avoid allocating/freeing native memory per frame
        if (bgrFrame == null) {
            bgrFrame = av_frame_alloc();
        }
        AVFrame bgr = bgrFrame;

        int needed = av_image_get_buffer_size(AV_PIX_FMT_BGR24, outW, outH, 1);
        if (bgrBuffer == null || bgrBufferSize < needed || outW != lastOutW || outH != lastOutH) {
            if (bgrBuffer != null) {
                bgrBuffer.deallocate();
            }
            bgrBuffer = new BytePointer(needed);  // JavaCV internally calls av_malloc, managed by the BytePointer deallocator
            bgrBufferSize = needed;
            lastOutW = outW;
            lastOutH = outH;
            av_image_fill_arrays(bgr.data(), bgr.linesize(), bgrBuffer,
                    AV_PIX_FMT_BGR24, outW, outH, 1);
        }

        sws_scale(swsCtx, frame.data(), frame.linesize(), 0, srcH,
                bgr.data(), bgr.linesize());

        BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
        byte[] row = new byte[outW * 3];
        BytePointer plane = bgr.data(0);
        int linesize = bgr.linesize(0);
        for (int y = 0; y < outH; y++) {
            plane.position((long) y * linesize).get(row);
            image.getRaster().setDataElements(0, y, outW, 1, row);
        }
        return image;
    }

    @Override
    public synchronized void close() {
        closeInternal();
    }

    private void closeInternal() {
        if (swsCtx != null) {
            sws_freeContext(swsCtx);
            swsCtx = null;
        }
        if (parser != null) {
            av_parser_close(parser);
            parser = null;
        }
        if (bgrFrame != null) {
            av_frame_free(bgrFrame);
            bgrFrame = null;
        }
        if (bgrBuffer != null) {
            bgrBuffer.deallocate();
            bgrBuffer = null;
        }
        bgrBufferSize = 0;
        if (codecCtx != null) {
            // Clear the extradata pointer to prevent a double-free between the internal
            // av_freep of avcodec_free_context and the JavaCV BytePointer deallocator
            codecCtx.extradata((BytePointer) null);
            codecCtx.extradata_size(0);
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        // extradata memory is freed by av_freep inside avcodec_free_context;
        // drop the extradataPtr reference so the JavaCV deallocator can handle it safely
        extradataPtr = null;
    }

    public long getFrameCount() {
        return frameCount;
    }
}
