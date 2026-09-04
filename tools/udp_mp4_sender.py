#!/usr/bin/env python3
"""
UDP HEVC video stream sender test tool -- MP4 input version.

Demuxes an MP4 (HEVC), converts length-prefixed (HVCC) packets to Annex-B (start codes),
and sends them fragmented according to the UDP protocol.

UDP packet format (big-endian):
    frame_id (uint16) + frag_id (uint16) + total_bytes (uint32) + HEVC data

Dependencies: pip install av  (PyAV)

Usage:
    python udp_mp4_sender.py "video.mp4"
    python udp_mp4_sender.py "video.mp4" --host 127.0.0.1 --port 3334
    python udp_mp4_sender.py "video.mp4" --speed 5        # send at 5x speed
    python udp_mp4_sender.py "video.mp4" --max-frames 300 # send only the first 300 frames

host/port default to the "udp" section of config.json.
"""

import argparse
import json
import os
import socket
import struct
import sys
import time

import av


def _config_path() -> str:
    """config.json lives in the project root (this script is under tools/)."""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")


def load_udp_config() -> dict:
    """Read the "udp" section from config.json to use as defaults."""
    try:
        with open(_config_path(), encoding="utf-8") as f:
            return json.load(f).get("udp", {})
    except Exception:
        return {}


def packet_to_annexb(packet_bytes: bytes) -> bytes:
    """Convert MP4 length-prefixed (HVCC) packets to Annex-B (start codes 00 00 00 01).
    Each HEVC NAL in MP4 is preceded by a 4-byte big-endian length."""
    out = bytearray()
    i = 0
    n = len(packet_bytes)
    while i + 4 <= n:
        length = int.from_bytes(packet_bytes[i:i + 4], "big")
        i += 4
        if length <= 0 or i + length > n:
            break  # incomplete data; stop
        out += b"\x00\x00\x00\x01"
        out += packet_bytes[i:i + length]
        i += length
    return bytes(out)


def parse_hvcc(extradata: bytes) -> list[tuple[int, bytes]]:
    """Parse hvcC (HEVCDecoderConfigurationRecord); returns [(nal_type, annexb), ...].

    Parameter sets (VPS/SPS/PPS) usually live only in the MP4 extradata, not in the packet
    stream, so they must be parsed out and prepended to frames for the receiver's decoder
    to initialize.
    """
    arrays = []
    if len(extradata) < 23:
        return arrays
    num_arrays = extradata[22]          # 23-byte fixed header, then the parameter set arrays
    pos = 23
    for _ in range(num_arrays):
        if pos + 3 > len(extradata):
            break
        nal_type = extradata[pos] & 0x3F
        num_nalus = int.from_bytes(extradata[pos + 1:pos + 3], "big")
        pos += 3
        for _ in range(num_nalus):
            if pos + 2 > len(extradata):
                return arrays
            nalu_len = int.from_bytes(extradata[pos:pos + 2], "big")
            pos += 2
            if pos + nalu_len > len(extradata):
                return arrays
            nalu = extradata[pos:pos + nalu_len]
            pos += nalu_len
            arrays.append((nal_type, b"\x00\x00\x00\x01" + nalu))
    return arrays


def build_param_prefix(extradata: bytes) -> bytes:
    """Extract VPS/SPS/PPS from hvcC and assemble them into an Annex-B prefix."""
    vps = sps = pps = None
    for nal_type, annexb in parse_hvcc(extradata):
        if nal_type == 32 and vps is None:
            vps = annexb
        elif nal_type == 33 and sps is None:
            sps = annexb
        elif nal_type == 34 and pps is None:
            pps = annexb
    prefix = b""
    if vps: prefix += vps
    if sps: prefix += sps
    if pps: prefix += pps
    return prefix


def send_mp4(path: str, host: str, port: int, frag_size: int = 1400,
             speed: float = 1.0, max_frames: int | None = None,
             stats_interval: float = 2.0):
    container = av.open(path)
    stream = container.streams.video[0]
    codec_ctx = stream.codec_context
    fps = float(stream.average_rate) if stream.average_rate else 30.0
    frame_interval = (1.0 / fps / speed) if fps > 0 and speed > 0 else 0.03
    total_frames = stream.frames if stream.frames else None

    print("══════════════════════════════════════════")
    print("  UDP HEVC 发送工具 (MP4)")
    print("══════════════════════════════════════════")
    print(f"  文件:     {path}")
    print(f"  编码:     {codec_ctx.name}  分辨率: {codec_ctx.width}x{codec_ctx.height}")
    print(f"  帧率:     {fps:.1f}  总帧: {total_frames}")
    print(f"  目标:     {host}:{port}")
    print(f"  倍速:     {speed}x  {'(实时)' if speed == 1.0 else ''}")
    print(f"  分片大小: {frag_size} B")
    print("══════════════════════════════════════════")

    # Extract VPS/SPS/PPS from hvcC and prepend them to the first frame and key frames (the packet stream itself carries no parameter sets)
    extradata = bytes(stream.codec_context.extradata) if stream.codec_context.extradata else b""
    param_prefix = build_param_prefix(extradata)
    if param_prefix:
        print(f"  参数集:   VPS/SPS/PPS 已从 hvcC 提取 ({len(param_prefix)} B)")
    else:
        print("  警告:     hvcC 中未找到 VPS/SPS/PPS")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sent_packets = 0
    sent_frames = 0
    bytes_sent = 0
    stat_start = time.time()

    frame_id = 0
    try:
        for packet in container.demux(stream):
            if max_frames and frame_id >= max_frames:
                break
            raw = bytes(packet)
            annexb = packet_to_annexb(raw)
            if not annexb:
                continue
            if frame_id == 0 or packet.is_keyframe:
                annexb = param_prefix + annexb

            total = len(annexb)
            offset = 0
            frag_id = 0
            while offset < total:
                chunk = annexb[offset:offset + frag_size]
                offset += len(chunk)
                hdr = struct.pack("!HHI", frame_id, frag_id, total)
                sock.sendto(hdr + chunk, (host, port))
                frag_id += 1
                sent_packets += 1
                bytes_sent += len(chunk)

            sent_frames += 1
            frame_id += 1

            if frame_interval > 0:
                time.sleep(frame_interval)

            elapsed = time.time() - stat_start
            if elapsed >= stats_interval:
                total = total_frames or "?"
                print(f"[{time.strftime('%H:%M:%S')}] 帧 {sent_frames}/{total} | "
                      f"包 {sent_packets} | {sent_frames / elapsed:.1f} fps | "
                      f"{bytes_sent * 8 / 1000 / elapsed:.0f} kbps")
                stat_start = time.time()
    except KeyboardInterrupt:
        print("\n[中断] 停止发送")
    finally:
        sock.close()
        container.close()

    print(f"[完成] 已发送 {sent_frames} 帧, {sent_packets} 个 UDP 包")


def main():
    cfg = load_udp_config()
    parser = argparse.ArgumentParser(
        description="UDP HEVC 视频流发送工具 (MP4)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python udp_mp4_sender.py "视频.mp4"
  python udp_mp4_sender.py "视频.mp4" --speed 5
  python udp_mp4_sender.py "视频.mp4" --max-frames 300
        """,
    )
    parser.add_argument("file", help="HEVC MP4 文件路径")
    parser.add_argument("--host", default=None,
                        help=f"目标主机 (默认: {cfg.get('host', '127.0.0.1')})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"目标端口 (默认: {cfg.get('port', 3334)})")
    parser.add_argument("--frag-size", type=int, default=1400,
                        help="每个 UDP 分片最大字节数 (默认: 1400)")
    parser.add_argument("--speed", type=float, default=1.0,
                        help="发送倍速 (默认 1.0 = 实时, 2 = 2倍速)")
    parser.add_argument("--max-frames", type=int, default=None,
                        help="只发送前 N 帧")

    args = parser.parse_args()
    host = args.host if args.host is not None else cfg.get("host", "127.0.0.1")
    port = args.port if args.port is not None else cfg.get("port", 3334)

    send_mp4(args.file, host, port, args.frag_size, args.speed, args.max_frames)


if __name__ == "__main__":
    main()
