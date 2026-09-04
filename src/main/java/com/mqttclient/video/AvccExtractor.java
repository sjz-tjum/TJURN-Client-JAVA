package com.mqttclient.video;

import java.io.ByteArrayOutputStream;

/**
 * Extracts SPS (NAL type 7) and PPS (NAL type 8) from an Annex-B H.264 byte
 * stream and builds an avcC-format parameter set (ISO/IEC 14496-15).
 *
 * <p>Strictly corresponds to _extract_and_convert_avcc in the Python
 * processor_thread.py.
 */
public final class AvccExtractor {

    private AvccExtractor() {
    }

    /**
     * Finds a start code, returning its position or -1 if not found.
     * Corresponds to _find_nal_start_code.
     */
    static int findStartCode(byte[] buf, int start) {
        for (int i = Math.max(0, start); i < buf.length - 3; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0) {
                if (buf[i + 2] == 1) {
                    return i;
                }
                if (buf[i + 2] == 0 && buf[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Strips the start code preceding the NAL, returning the bare NAL body. */
    private static byte[] strip(byte[] nal) {
        if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) {
            byte[] out = new byte[nal.length - 4];
            System.arraycopy(nal, 4, out, 0, out.length);
            return out;
        }
        if (nal.length >= 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) {
            byte[] out = new byte[nal.length - 3];
            System.arraycopy(nal, 3, out, 0, out.length);
            return out;
        }
        return nal;
    }

    /**
     * Extracts SPS/PPS from Annex-B bytes, returning the avcC-format bytes, or
     * null on failure.
     *
     * @param h264Data H.264 data in Annex-B format
     * @return avcC byte array, or null (SPS and PPS not both found)
     */
    public static byte[] extractAvcc(byte[] h264Data) {
        byte[] sps = null;
        byte[] pps = null;
        int i = 0;

        while (i < h264Data.length - 4) {
            int start = findStartCode(h264Data, i);
            if (start == -1) {
                break;
            }
            int nalLen;
            if (h264Data[start] == 0 && h264Data[start + 1] == 0
                    && h264Data[start + 2] == 0 && h264Data[start + 3] == 1) {
                nalLen = 4;
            } else if (h264Data[start] == 0 && h264Data[start + 1] == 0 && h264Data[start + 2] == 1) {
                nalLen = 3;
            } else {
                i = start + 1;
                continue;
            }
            int nalTypePos = start + nalLen;
            if (nalTypePos >= h264Data.length) {
                break;
            }
            int nalType = h264Data[nalTypePos] & 0x1F;

            int nextStart = findStartCode(h264Data, start + 1);
            int nalEnd = (nextStart != -1) ? nextStart : h264Data.length;

            if (nalType == 7) {
                sps = java.util.Arrays.copyOfRange(h264Data, start, nalEnd);
            } else if (nalType == 8) {
                pps = java.util.Arrays.copyOfRange(h264Data, start, nalEnd);
            }

            if (sps != null && pps != null) {
                break;
            }
            i = nalEnd;
        }

        if (sps == null || pps == null) {
            return null;
        }

        byte[] spsBody = strip(sps);
        byte[] ppsBody = strip(pps);

        // Build avcC
        ByteArrayOutputStream avcc = new ByteArrayOutputStream();
        avcc.write(0x01);                       // configurationVersion
        avcc.write(spsBody[1] & 0xFF);          // profile_idc
        avcc.write(spsBody[2] & 0xFF);          // profile_compatibility
        avcc.write(spsBody[3] & 0xFF);          // level_idc
        avcc.write(0xFF);                       // lengthSizeMinusOne = 3
        avcc.write(0xE1);                       // 1 SPS (0xE0 | numSPS)
        avcc.write((spsBody.length >> 8) & 0xFF);
        avcc.write(spsBody.length & 0xFF);
        avcc.write(spsBody, 0, spsBody.length);
        avcc.write(0x01);                       // 1 PPS
        avcc.write((ppsBody.length >> 8) & 0xFF);
        avcc.write(ppsBody.length & 0xFF);
        avcc.write(ppsBody, 0, ppsBody.length);
        return avcc.toByteArray();
    }
}
