package com.mqttclient.video;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * UDP HEVC 分片帧重组器。
 *
 * <p>按 {@code frame_id} 缓存分片，当收集字节数达到 {@code total_bytes} 时
 * 按 {@code frag_id} 顺序拼接输出完整帧。线程安全，可被多个线程调用。
 */
public class UdpFrameReassembler {

    private final Map<Integer, PendingFrame> pendingFrames = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final Consumer<String> warn;

    /**
     * @param timeoutMs 帧超时时间（超过则丢弃）
     * @param warn      丢弃超时帧时的告警回调
     */
    public UdpFrameReassembler(long timeoutMs, Consumer<String> warn) {
        this.timeoutMs = timeoutMs;
        this.warn = warn;
    }

    /**
     * 添加一个分片。
     *
     * @return 若该帧已收齐则返回按序拼接的完整帧字节（并移除缓存），否则返回 null
     */
    public byte[] addFragment(int frameId, int fragId, byte[] data, int totalBytes) {
        PendingFrame pf = pendingFrames.computeIfAbsent(frameId, k -> new PendingFrame(totalBytes));
        pf.lastUpdate = System.currentTimeMillis();
        // 以后到的非零 totalBytes 为准
        if (totalBytes > 0 && pf.totalBytes == 0) {
            pf.totalBytes = totalBytes;
        }
        // 同一分片去重
        pf.fragments.putIfAbsent(fragId, data);

        if (pf.isComplete()) {
            pendingFrames.remove(frameId);
            return pf.assemble();
        }
        return null;
    }

    /**
     * 清理超时未收齐的帧。
     *
     * @return 丢弃的帧数
     */
    public int cleanupStale(long now) {
        int removed = 0;
        var it = pendingFrames.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            PendingFrame pf = entry.getValue();
            if (now - pf.lastUpdate > timeoutMs) {
                it.remove();
                removed++;
                if (warn != null) {
                    warn.accept("帧 #" + entry.getKey() + " 超时丢弃 ("
                            + pf.collectedBytes() + "/" + pf.totalBytes + " bytes)");
                }
            }
        }
        return removed;
    }

    /** 当前未完成帧数。 */
    public int pendingCount() {
        return pendingFrames.size();
    }

    public void clear() {
        pendingFrames.clear();
    }

    /** 正在重组中的帧。 */
    private static class PendingFrame {
        final TreeMap<Integer, byte[]> fragments = new TreeMap<>();
        int totalBytes;
        long lastUpdate = System.currentTimeMillis();

        PendingFrame(int totalBytes) {
            this.totalBytes = totalBytes;
        }

        int collectedBytes() {
            int sum = 0;
            for (byte[] f : fragments.values()) sum += f.length;
            return sum;
        }

        boolean isComplete() {
            return totalBytes > 0 && collectedBytes() >= totalBytes;
        }

        byte[] assemble() {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(totalBytes);
            for (byte[] f : fragments.values()) {
                try { bos.write(f); } catch (Exception ignore) {}
            }
            return bos.toByteArray();
        }
    }
}
