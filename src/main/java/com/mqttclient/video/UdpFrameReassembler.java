package com.mqttclient.video;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reassembles UDP HEVC frames from fragments.
 *
 * <p>Caches fragments by {@code frame_id}; when the collected byte count reaches
 * {@code total_bytes}, concatenates them in {@code frag_id} order to emit the complete
 * frame. Thread-safe and callable from multiple threads.
 */
public class UdpFrameReassembler {

    private final Map<Integer, PendingFrame> pendingFrames = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final Consumer<String> warn;

    /**
     * @param timeoutMs frame timeout (dropped when exceeded)
     * @param warn      warning callback invoked when a timed-out frame is dropped
     */
    public UdpFrameReassembler(long timeoutMs, Consumer<String> warn) {
        this.timeoutMs = timeoutMs;
        this.warn = warn;
    }

    /**
     * Adds a fragment.
     *
     * @return the reassembled frame bytes (removing the frame from the cache) if the
     *         frame is now complete, otherwise {@code null}
     */
    public byte[] addFragment(int frameId, int fragId, byte[] data, int totalBytes) {
        PendingFrame pf = pendingFrames.computeIfAbsent(frameId, k -> new PendingFrame(totalBytes));
        pf.lastUpdate = System.currentTimeMillis();
        // A later non-zero totalBytes takes precedence
        if (totalBytes > 0 && pf.totalBytes == 0) {
            pf.totalBytes = totalBytes;
        }
        // Deduplicate identical fragments
        pf.fragments.putIfAbsent(fragId, data);

        if (pf.isComplete()) {
            pendingFrames.remove(frameId);
            return pf.assemble();
        }
        return null;
    }

    /**
     * Cleans up frames not completed within the timeout.
     *
     * @return the number of dropped frames
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

    /** Number of currently incomplete frames. */
    public int pendingCount() {
        return pendingFrames.size();
    }

    public void clear() {
        pendingFrames.clear();
    }

    /** A frame currently being reassembled. */
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
