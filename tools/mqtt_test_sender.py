#!/usr/bin/env python3
"""
MQTT 视频流发送测试工具 —— 发送 H.264 视频到 MQTT。

从 MP4 (任意编码) 解封装 → 用 libx264 转码成 H.264 Annex-B →
按 290 字节分片 → 打包 VideoStreamData + protobuf CustomByteBlock → 发布到 MQTT。

协议（与 Java 客户端一致）:
    MQTT 载荷 = CustomByteBlock { bytes data = VideoStreamData(300字节) }
    VideoStreamData = seq_id(uint16 LE) + timestamp(int64 LE) + h264分片(290字节)

依赖: pip install av paho-mqtt

用法:
    python mqtt_test_sender.py "视频.mp4"
    python mqtt_test_sender.py "视频.mp4" --speed 2
    python mqtt_test_sender.py "视频.mp4" --topic CustomByteBlock

broker 地址/主题默认从 config.json 的 mqtt 段读取。
"""

import argparse
import json
import os
import struct
import sys
import time

import av
import paho.mqtt.client as mqtt

# ── 协议常量（与 Java Constants 一致）──────────────────────────────────
PACKET_SIZE = 300
HEADER_SIZE = 10
H264_CHUNK_SIZE = 290
STRUCT_FMT = "<Hq290s"   # seq_id(u16) + timestamp(i64) + 290B chunk
VIDEO_DATA_SIZE = 2 + 8 + 290


def _config_path() -> str:
    """config.json 在项目根目录（本脚本位于 tools/ 下）。"""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")


def load_mqtt_config() -> dict:
    """从 config.json 读取 mqtt 段。"""
    try:
        with open(_config_path(), encoding="utf-8") as f:
            return json.load(f).get("mqtt", {})
    except Exception:
        return {}


def encode_varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def wrap_custom_byte_block(data: bytes) -> bytes:
    """构造 CustomByteBlock protobuf 载荷（field 1 = bytes）。"""
    return b"\x0a" + encode_varint(len(data)) + data


def chunk_into_290(stream: bytes) -> list[bytes]:
    """把 H.264 字节流切成 290 字节块，末尾不足补 0。"""
    chunks = []
    for i in range(0, len(stream), H264_CHUNK_SIZE):
        block = stream[i:i + H264_CHUNK_SIZE]
        if len(block) < H264_CHUNK_SIZE:
            block += b"\x00" * (H264_CHUNK_SIZE - len(block))
        chunks.append(block)
    return chunks


def transcode_to_h264(mp4_path: str, max_frames: int | None = None) -> tuple[bytes, float]:
    """把 MP4 转码成 H.264 Annex-B 字节流。返回 (annexb_bytes, duration_s)。"""
    import tempfile
    import os

    in_c = av.open(mp4_path)
    in_s = in_c.streams.video[0]
    fps = float(in_s.average_rate) if in_s.average_rate else 30.0
    duration = float(in_s.duration * in_s.time_base) if in_s.duration else 0.0

    tmp_fd, tmp_path = tempfile.mkstemp(suffix=".h264")
    os.close(tmp_fd)
    try:
        from fractions import Fraction
        out_c = av.open(tmp_path, "w", format="h264")
        out_s = out_c.add_stream("libx264", rate=Fraction(int(fps), 1))
        out_s.width = in_s.codec_context.width
        out_s.height = in_s.codec_context.height
        out_s.pix_fmt = "yuv420p"
        out_s.bit_rate = 4_000_000
        out_s.gop_size = 30
        out_s.max_b_frames = 0     # 屏幕录制通常无 B 帧，解码更稳
        out_s.options = {"preset": "veryfast", "tune": "zerolatency"}

        encoded = 0
        for frame in in_c.decode(in_s):
            if max_frames and encoded >= max_frames:
                break
            frame.pts = None
            for packet in out_s.encode(frame):
                out_c.mux(packet)
            encoded += 1
        for packet in out_s.encode(None):   # 冲刷
            out_c.mux(packet)
        out_c.close()
        in_c.close()

        if max_frames and max_frames < 0:
            max_frames = None
        with open(tmp_path, "rb") as f:
            data = f.read()
        # 若限制了帧数，时长按比例估算
        if max_frames and encoded > 0:
            duration = duration * encoded / (in_s.frames or encoded)
        return data, duration
    finally:
        os.unlink(tmp_path)


def send_h264_stream(h264: bytes, duration: float, broker_host: str, broker_port: int,
                     topic: str, speed: float = 1.0):
    chunks = chunk_into_290(h264)
    total_chunks = len(chunks)
    print(f"  H.264 流: {len(h264)} B, 切成 {total_chunks} 个 290B 分片")
    if total_chunks == 0:
        print("  错误: 没有可发送的分片")
        return

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                         client_id="mqtt_test_sender", clean_session=True)
    client.connect(broker_host, broker_port, 60)

    # 节奏：按视频时长摊开（speed=1 实时）
    if duration > 0 and speed > 0:
        interval = duration / total_chunks / speed
    else:
        interval = 0.01

    seq_id = 0
    base_ts = int(time.time() * 1000)
    sent = 0
    start = time.time()
    try:
        for chunk in chunks:
            ts = base_ts + int((time.time() - start) * 1000)
            vsd = struct.pack(STRUCT_FMT, seq_id & 0xFFFF, ts, chunk)  # VideoStreamData
            payload = wrap_custom_byte_block(vsd)                      # CustomByteBlock
            client.publish(topic, payload, 0)
            seq_id += 1
            sent += 1
            if interval > 0:
                time.sleep(interval)
            if sent % 500 == 0:
                print(f"[{time.strftime('%H:%M:%S')}] 已发布 {sent}/{total_chunks} 分片")
    except KeyboardInterrupt:
        print("\n[中断]")
    finally:
        client.disconnect()
        elapsed = time.time() - start
        print(f"[完成] 发布 {sent} 个分片, 耗时 {elapsed:.1f}s, "
              f"{sent / elapsed:.0f} msg/s → {topic}")


def main():
    cfg = load_mqtt_config()
    parser = argparse.ArgumentParser(
        description="MQTT H.264 视频流发送工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python mqtt_test_sender.py "视频.mp4"
  python mqtt_test_sender.py "视频.mp4" --speed 2
        """,
    )
    parser.add_argument("file", help="MP4 视频文件路径")
    parser.add_argument("--host", default=None,
                        help=f"broker 地址 (默认: {cfg.get('host', '127.0.0.1')})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"broker 端口 (默认: {cfg.get('port', 1883)})")
    parser.add_argument("--topic", default=None,
                        help=f"发布主题 (默认: {cfg.get('topics', ['CustomByteBlock'])[0]})")
    parser.add_argument("--speed", type=float, default=1.0, help="倍速 (默认 1.0=实时)")
    parser.add_argument("--max-frames", type=int, default=None, help="只转码/发送前 N 帧 (快速测试)")

    args = parser.parse_args()
    host = args.host if args.host is not None else cfg.get("host", "127.0.0.1")
    port = args.port if args.port is not None else cfg.get("port", 1883)
    topic = args.topic if args.topic is not None else cfg.get("topics", ["CustomByteBlock"])[0]

    print("══════════════════════════════════════════")
    print("  MQTT H.264 视频流发送工具")
    print("══════════════════════════════════════════")
    print(f"  文件:   {args.file}")
    print(f"  Broker: {host}:{port}")
    print(f"  主题:   {topic}")
    print("  转码:   MP4 → H.264 (libx264) → 290B 分片 → protobuf")
    print("══════════════════════════════════════════")

    h264, duration = transcode_to_h264(args.file, args.max_frames)
    print(f"  转码完成: {len(h264)} B H.264 Annex-B, 视频时长 {duration:.1f}s")
    send_h264_stream(h264, duration, host, port, topic, args.speed)


if __name__ == "__main__":
    main()
