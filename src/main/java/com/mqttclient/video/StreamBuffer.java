package com.mqttclient.video;

import java.util.Arrays;

/**
 * 可增长的字节缓冲，支持从头部删除已消费字节 —— 对应 Python 版的 {@code bytearray}
 * 及其 {@code del buffer[:n]} / {@code extend} / {@code clear} 操作。
 *
 * <p>非线程安全；仅在 {@link VideoProcessor} 的处理线程内使用。
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

    /** 当前字节数。 */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** 读取指定位置的字节（无符号 0-255）。 */
    public int get(int index) {
        return data[index] & 0xFF;
    }

    /** 追加数据（对应 extend）。 */
    public void extend(byte[] src) {
        ensureCapacity(size + src.length);
        System.arraycopy(src, 0, data, size, src.length);
        size += src.length;
    }

    /** 删除前 n 个字节（对应 {@code del buffer[:n]}）。 */
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

    /** 清空（对应 clear）。 */
    public void clear() {
        size = 0;
    }

    /** 拷贝出 [from, to) 区间的字节。 */
    public byte[] copyRange(int from, int to) {
        return Arrays.copyOfRange(data, from, to);
    }

    /** 拷贝出全部有效字节。 */
    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * 查找 Annex-B 起始码（0x000001 或 0x00000001），返回起始位置，未找到返回 -1。
     * 对应 Python 版的 _find_start_code / _find_nal_start_code。
     */
    public int findStartCode(int start) {
        for (int i = Math.max(0, start); i < size - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i; // 3 字节起始码
                }
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    return i; // 4 字节起始码
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
