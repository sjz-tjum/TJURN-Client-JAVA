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
 * 基于 JavaCV / FFmpeg 的 H.264 解码器。
 *
 * <p>对应 Python 版 video/h264_decoder.py (PyAV 实现)：
 * <ul>
 *   <li>创建 H.264 解码器上下文，多线程 (thread_count=8)、低延迟</li>
 *   <li>支持注入 avcC extradata (SPS/PPS)，重置时复用缓存</li>
 *   <li>按 Annex-B 起始码切分完整 NAL，逐包解码</li>
 *   <li>输出 BGR24，转 300x300 的 BufferedImage</li>
 * </ul>
 */
public class H264Decoder implements VideoDecoder {

    private final int width;
    private final int height;

    private AVCodec codec;
    private AVCodecContext codecCtx;
    private AVCodecParserContext parser;
    private SwsContext swsCtx;

    private byte[] cachedExtradata;   // 缓存 avcC，重置时复用
    private long frameCount = 0;

    // ==== 调试：hex dump 收到的原始 H.264 流 ====
    private java.io.FileOutputStream dumpStream;

    /**
     * 开始将解码器收到的原始 Annex-B 数据保存到文件，用于离线分析。
     * 文件可用 ffplay / ffprobe 直接播放或检查。
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

    /** 持有 BytePointer 引用，防止 GC 释放后 codecCtx.extradata 变成悬空指针。 */
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

            // 注入缓存的 extradata
            if (cachedExtradata != null && cachedExtradata.length > 0) {
                extradataPtr = new BytePointer(cachedExtradata.length + AV_INPUT_BUFFER_PADDING_SIZE);
                extradataPtr.put(cachedExtradata, 0, cachedExtradata.length);
                // 填充区置零（new BytePointer 已自动清零，此处显式确保安全）
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
     * 从缓冲区提取所有完整 NAL（含起始码），返回已消费字节数。
     * 对应 _split_complete_nalus：最后一个不完整 NAL 会保留。
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
                break; // 最后一个 NAL 可能不完整，保留
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

        // 对齐起始码
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
            // 只保留最新 5 帧（对应 images[-5:]）
            while (images.size() > 5) {
                images.remove(0);
            }
        } catch (Exception e) {
            System.out.printf("[Decoder] 解码异常: %s，保留数据等待下一个 IDR%n", e.getMessage());
        }
        return images;
    }

    /** 用 parser + decoder 处理一段 Annex-B 数据。 */
    private void decodeAnnexB(byte[] data, List<BufferedImage> out) {
        // 调试：保存原始数据到文件
        if (dumpStream != null) {
            try {
                dumpStream.write(data);
                dumpStream.flush();
            } catch (java.io.IOException ignored) {}
        }

        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();

        try {
            // 使用 av_new_packet: 内部用 av_malloc 分配，av_packet_free 用 av_free 释放
            // 分配器一致，不会出现 malloc/av_free 混用的堆损坏
            int paddedSize = data.length + AV_INPUT_BUFFER_PADDING_SIZE;
            if (av_new_packet(pkt, paddedSize) < 0) {
                System.err.println("[Decoder] av_new_packet 分配失败");
                return;
            }
            // 拷贝数据（av_new_packet 已经将缓冲区清零，填充区自动为 0）
            pkt.data().put(data, 0, data.length);
            pkt.size(data.length);

            decodePacket(pkt, frame, out);
        } catch (Exception e) {
            System.err.println("[Decoder] 解码异常: " + e.getMessage());
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);   // 安全释放 av_malloc 内存
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

    /** AVFrame (YUV) -> BGR24 -> BufferedImage，必要时缩放到 width x height。 */
    private BufferedImage frameToImage(AVFrame frame) {
        int srcW = frame.width();
        int srcH = frame.height();
        if (srcW <= 0 || srcH <= 0) {
            return null;
        }

        swsCtx = sws_getCachedContext(swsCtx,
                srcW, srcH, codecCtx.pix_fmt(),
                width, height, AV_PIX_FMT_BGR24,
                SWS_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null) {
            return null;
        }

        // 复用 bgrFrame + bgrBuffer，避免每帧分配/释放 native 内存
        if (bgrFrame == null) {
            bgrFrame = av_frame_alloc();
        }
        AVFrame bgr = bgrFrame;

        int needed = av_image_get_buffer_size(AV_PIX_FMT_BGR24, width, height, 1);
        if (bgrBuffer == null || bgrBufferSize < needed) {
            if (bgrBuffer != null) {
                bgrBuffer.deallocate();
            }
            bgrBuffer = new BytePointer(needed);  // JavaCV 内部调用 av_malloc，由 BytePointer deallocator 管理
            bgrBufferSize = needed;
            av_image_fill_arrays(bgr.data(), bgr.linesize(), bgrBuffer,
                    AV_PIX_FMT_BGR24, width, height, 1);
        }

        sws_scale(swsCtx, frame.data(), frame.linesize(), 0, srcH,
                bgr.data(), bgr.linesize());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] row = new byte[width * 3];
        BytePointer plane = bgr.data(0);
        int linesize = bgr.linesize(0);
        for (int y = 0; y < height; y++) {
            plane.position((long) y * linesize).get(row);
            image.getRaster().setDataElements(0, y, width, 1, row);
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
            // 清除 extradata 指针，防止 avcodec_free_context 内部 av_freep 与
            // JavaCV BytePointer deallocator 产生 double-free
            codecCtx.extradata((BytePointer) null);
            codecCtx.extradata_size(0);
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        // extradata 内存由 avcodec_free_context 的 av_freep 释放，
        // 释放 extradataPtr 引用以便 JavaCV deallocator 可安全处理
        extradataPtr = null;
    }

    public long getFrameCount() {
        return frameCount;
    }
}
