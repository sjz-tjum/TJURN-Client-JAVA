package com.mqttclient.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN;

/**
 * 基于 JavaCV / FFmpeg 的 HEVC (H.265) 解码器。
 *
 * <p>与 {@link H264Decoder} 架构一致，使用 {@code AV_CODEC_ID_HEVC}。
 * Annex-B 流输入，自动从流内 VPS/SPS/PPS 自举，输出 BGR24 → BufferedImage。
 */
public class HevcDecoder implements VideoDecoder {

    private final int width;
    private final int height;

    private AVCodec codec;
    private AVCodecContext codecCtx;
    private SwsContext swsCtx;

    private byte[] cachedExtradata;
    private long frameCount;

    // extradata 指针（防 GC 回收导致悬空指针）
    private BytePointer extradataPtr;

    public HevcDecoder(int width, int height) {
        this.width = width;
        this.height = height;
        resetCodec("init");
    }

    @Override
    public void setExtradata(byte[] hvcC) {
        this.cachedExtradata = hvcC;
        resetCodec("set extradata");
        System.out.println("[HEVC] 已接收外部 extradata 并缓存");
    }

    private synchronized void resetCodec(String reason) {
        closeInternal();
        try {
            codec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
            if (codec == null || codec.isNull()) {
                System.out.println("[HEVC] 找不到 HEVC 解码器");
                return;
            }
            codecCtx = avcodec_alloc_context3(codec);
            codecCtx.thread_type(2);
            codecCtx.thread_count(8);
            codecCtx.flags(codecCtx.flags() | AV_CODEC_FLAG_LOW_DELAY);

            if (cachedExtradata != null && cachedExtradata.length > 0) {
                extradataPtr = new BytePointer(cachedExtradata.length + AV_INPUT_BUFFER_PADDING_SIZE);
                extradataPtr.put(cachedExtradata, 0, cachedExtradata.length);
                codecCtx.extradata(extradataPtr);
                codecCtx.extradata_size(cachedExtradata.length);
                System.out.println("[HEVC] 已注入缓存的 extradata");
            } else {
                System.out.println("[HEVC] 暂无 extradata，依赖流内参数集");
            }

            if (avcodec_open2(codecCtx, codec, (PointerPointer<?>) null) < 0) {
                System.out.println("[HEVC] 打开解码器失败");
                codecCtx = null;
                return;
            }
            System.out.printf("[HEVC] 解码器已重置 (原因: %s)%n", reason);
        } catch (Exception e) {
            System.out.printf("[HEVC] 创建解码器失败: %s%n", e.getMessage());
            codecCtx = null;
        }
    }

    @Override
    public void reset() {
        resetCodec("manual reset");
    }

    @Override
    public synchronized List<BufferedImage> parseAndDecode(StreamBuffer buffer) {
        List<BufferedImage> images = new ArrayList<>();
        if (codecCtx == null || buffer.isEmpty()) {
            return images;
        }

        int startPos = buffer.findStartCode(0);
        if (startPos < 0) {
            if (buffer.size() > 500_000) {
                System.out.println("[HEVC] 无起始码且过大，清空");
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

        byte[] data = buffer.copyRange(0, consumed);
        try {
            decodeAnnexB(data, images);
            buffer.deleteFront(consumed);
            while (images.size() > 5) {
                images.remove(0);
            }
        } catch (Exception e) {
            System.out.printf("[HEVC] 解码异常: %s，保留数据等待下一个 IDR%n", e.getMessage());
        }
        return images;
    }

    private int findConsumedLength(StreamBuffer buffer) {
        int consumed = 0;
        int i = 0;
        int size = buffer.size();
        while (i < size) {
            int start = buffer.findStartCode(i);
            if (start == -1) break;
            int nextStart = buffer.findStartCode(start + 1);
            if (nextStart == -1) break;
            consumed = nextStart;
            i = nextStart;
        }
        return consumed;
    }

    private void decodeAnnexB(byte[] data, List<BufferedImage> out) {
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        try {
            int paddedSize = data.length + AV_INPUT_BUFFER_PADDING_SIZE;
            if (av_new_packet(pkt, paddedSize) < 0) {
                System.err.println("[HEVC] av_new_packet 分配失败");
                return;
            }
            pkt.data().put(data, 0, data.length);
            pkt.size(data.length);
            decodePacket(pkt, frame, out);
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);
        }
    }

    private void decodePacket(AVPacket pkt, AVFrame frame, List<BufferedImage> out) {
        int ret = avcodec_send_packet(codecCtx, pkt);
        if (ret < 0) {
            System.err.println("[HEVC] avcodec_send_packet 失败, err=" + ret);
            return;
        }
        while (ret >= 0) {
            ret = avcodec_receive_frame(codecCtx, frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) break;
            if (ret < 0) {
                System.err.println("[HEVC] avcodec_receive_frame 失败, err=" + ret);
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
     * 把解码帧转成 BGR24 BufferedImage。
     *
     * <p>输出尺寸规则：构造时传入的 width/height 若 &gt; 0 则缩放为该固定尺寸；
     * 若为 0（UDP 模式，不受限）则按流本身分辨率输出，分辨率变化时自动重建缓冲。
     */
    private BufferedImage frameToImage(AVFrame frame) {
        int srcW = frame.width();
        int srcH = frame.height();
        if (srcW <= 0 || srcH <= 0) return null;

        int outW = (width > 0) ? width : srcW;
        int outH = (height > 0) ? height : srcH;

        swsCtx = sws_getCachedContext(swsCtx,
                srcW, srcH, codecCtx.pix_fmt(),
                outW, outH, AV_PIX_FMT_BGR24,
                SWS_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null) return null;

        if (bgrFrame == null) {
            bgrFrame = av_frame_alloc();
        }
        int needed = av_image_get_buffer_size(AV_PIX_FMT_BGR24, outW, outH, 1);
        if (bgrBuffer == null || bgrBufferSize < needed || outW != lastOutW || outH != lastOutH) {
            if (bgrBuffer != null) bgrBuffer.deallocate();
            bgrBuffer = new BytePointer(needed);
            bgrBufferSize = needed;
            lastOutW = outW;
            lastOutH = outH;
            av_image_fill_arrays(bgrFrame.data(), bgrFrame.linesize(), bgrBuffer,
                    AV_PIX_FMT_BGR24, outW, outH, 1);
        }

        sws_scale(swsCtx, frame.data(), frame.linesize(), 0, srcH,
                bgrFrame.data(), bgrFrame.linesize());

        BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
        byte[] row = new byte[outW * 3];
        BytePointer plane = bgrFrame.data(0);
        int linesize = bgrFrame.linesize(0);
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
        if (swsCtx != null) { sws_freeContext(swsCtx); swsCtx = null; }
        if (bgrFrame != null) { av_frame_free(bgrFrame); bgrFrame = null; }
        if (bgrBuffer != null) { bgrBuffer.deallocate(); bgrBuffer = null; }
        bgrBufferSize = 0;
        if (codecCtx != null) {
            codecCtx.extradata((BytePointer) null);
            codecCtx.extradata_size(0);
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        extradataPtr = null;
    }

    public long getFrameCount() { return frameCount; }
}
