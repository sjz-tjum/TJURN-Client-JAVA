package com.mqttclient.video;

import java.util.Arrays;

/**
 * Growable byte buffer that supports removing consumed bytes from the front -
 * corresponds to Python's {@code bytearray} and its {@code del buffer[:n]} /
 * {@code extend} / {@code clear} operations.
 *
 * <p>Not thread-safe; used only on the {@link VideoProcessor} processing thread.
 */
public class StreamBuffer {

    private byte[] data;
    private int size;

    public StreamBuffer() {
        this(1024);
    }

    public StreamBuffer(int initialCapacity) {
        this.data = new byte[Math.max(16, initialCapacity)];
        this.size = 0;
    }

    /** Current number of bytes. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Reads the byte at the given position (unsigned 0-255). */
    public int get(int index) {
        return data[index] & 0xFF;
    }

    /** Appends data (corresponds to extend). */
    public void extend(byte[] src) {
        ensureCapacity(size + src.length);
        System.arraycopy(src, 0, data, size, src.length);
        size += src.length;
    }

    /** Removes the first n bytes (corresponds to {@code del buffer[:n]}). */
    public void deleteFront(int n) {
        if (n <= 0) {
            return;
        }
        if (n >= size) {
            size = 0;
            return;
        }
        System.arraycopy(data, n, data, 0, size - n);
        size -= n;
    }

    /** Clears the buffer (corresponds to clear). */
    public void clear() {
        size = 0;
    }

    /** Copies the bytes in the [from, to) range. */
    public byte[] copyRange(int from, int to) {
        return Arrays.copyOfRange(data, from, to);
    }

    /** Copies all valid bytes. */
    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * Finds an Annex-B start code (0x000001 or 0x00000001), returning its position,
     * or -1 if not found. Corresponds to Python's _find_start_code /
     * _find_nal_start_code.
     *
     * <p>Note: the 4-byte start code {@code 0x00000001} contains the 3-byte substring
     * {@code 0x000001}. This method prefers the 4-byte start code; when a 3-byte
     * start code is detected at position {@code i}, it checks whether the previous
     * byte is 0 and, if so, skips it (that position is the tail of a 4-byte start
     * code).
     */
    public int findStartCode(int start) {
        for (int i = Math.max(0, start); i < size - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                // 4-byte start code 0x00000001
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
                // 3-byte start code 0x000001
                if (data[i + 2] == 1) {
                    // Skip the false 3-byte code embedded in the tail of a 4-byte start code
                    if (i > 0 && data[i - 1] == 0) {
                        continue;
                    }
                    return i;
                }
            }
        }
        return -1;
    }

    private void ensureCapacity(int min) {
        if (min > data.length) {
            int newCap = Math.max(min, data.length * 2);
            data = Arrays.copyOf(data, newCap);
        }
    }
}
