package com.mqttclient.ext;

/**
 * Raw-packet processing hook - fired after each video packet's struct has been parsed.
 *
 * <p>Extension point for custom parsing logic such as fragment reassembly (mirrors the disabled
 * video/packet_reassembler.py in the Python version), timestamp statistics, packet capture to disk,
 * etc. Multiple hooks are chained in registration order.
 */
@FunctionalInterface
public interface PacketProcessor {

    /**
     * @param seqId     packet sequence ID (uint16, 0-65535)
     * @param timestamp timestamp (microseconds)
     * @param h264Chunk the 290-byte H.264 chunk carried by this packet
     */
    void onPacket(int seqId, long timestamp, byte[] h264Chunk);
}
