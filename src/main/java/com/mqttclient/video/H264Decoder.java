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

    public H264Decoder(int width, int height) {
        this.width = width;
        this.height = height;
        resetCodec("init");
    }

    @Override
    public void setExtradata(byte[] avccBytes) {
        this.cachedExtradata = avccBytes;
        // FFmpeg 要求 extradata 在打开编解码器之前设置，故重建上下文再注入
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

            // 注入缓存的 extradata（对应 PyAV 的 codec.extradata = ...）
            if (cachedExtradata != null && cachedExtradata.length > 0) {
                BytePointer extra = new BytePointer(cachedExtradata.length + AV_INPUT_BUFFER_PADDING_SIZE);
                extra.put(cachedExtradata, 0, cachedExtradata.length);
                // 填充区清零
                for (int i = 0; i < AV_INPUT_BUFFER_PADDING_SIZE; i++) {
                    extra.put((long) cachedExtradata.length + i, (byte) 0);
                }
                codecCtx.extradata(extra);
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
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        IntPointer pktSize = new IntPointer(1);
        
        try {
            BytePointer dataPtr = new BytePointer(data);
            int offset = 0;
            int remaining = data.length;
            
            while (remaining > 0) {
                dataPtr.position(offset);
                
                int used = av_parser_parse2(
                    parser, 
                    codecCtx,
                    pkt.data(), pktSize,      // BytePointer, IntPointer
                    dataPtr, remaining,       // BytePointer, int
                    AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0
                );
                
                if (used < 0) {
                    break;
                }
                offset += used;
                remaining -= used;
                
                if (pktSize.get() > 0) {
                    pkt.size(pktSize.get());
                    decodePacket(pkt, frame, out);
                }
            }
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);
            pktSize.close();
        }
    }

    private void decodePacket(AVPacket pkt, AVFrame frame, List<BufferedImage> out) {
        int ret = avcodec_send_packet(codecCtx, pkt);
        if (ret < 0) {
            return;
        }
        while (ret >= 0) {
            ret = avcodec_receive_frame(codecCtx, frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) {
                break;
            }
            if (ret < 0) {
                break;
            }
            BufferedImage img = frameToImage(frame);
            if (img != null) {
                out.add(img);
                frameCount++;
            }
        }
    }

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

        AVFrame bgr = av_frame_alloc();
        try {
            int numBytes = av_image_get_buffer_size(AV_PIX_FMT_BGR24, width, height, 1);
            BytePointer bgrBuffer = new BytePointer(av_malloc(numBytes));
            av_image_fill_arrays(bgr.data(), bgr.linesize(), bgrBuffer,
                    AV_PIX_FMT_BGR24, width, height, 1);

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
            av_free(bgrBuffer);
            return image;
        } finally {
            av_frame_free(bgr);
        }
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
        if (codecCtx != null) {
            avcodec_free_context(codecCtx);
            codecCtx = null;
        }
    }

    public long getFrameCount() {
        return frameCount;
    }
}
