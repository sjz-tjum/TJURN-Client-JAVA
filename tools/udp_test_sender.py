#!/usr/bin/env python3
"""
UDP HEVC 视频流发送测试脚本。

向 UDP 3334 端口发送 HEVC 分片包，模拟视频流发送端。
包格式 (big-endian):
    frame_id (uint16) + frag_id (uint16) + total_bytes (uint32) + HEVC data

用法:
    # 发送一个测试文件
    python udp_test_sender.py --file test.hevc --host 127.0.0.1 --port 3334

    # 自动生成 HEVC 测试流 (需要 ffmpeg)
    python udp_test_sender.py --generate --host 127.0.0.1 --port 3334
"""

import argparse
import os
import socket
import struct
import subprocess
import sys
import time


def send_hevc_file(filepath: str, host: str, port: int, frag_size: int = 1400):
    """读取 HEVC Annex-B 文件，按帧切分后通过 UDP 发送。"""
    with open(filepath, "rb") as f:
        data = f.read()

    # 按 Annex-B 起始码切帧
    frames = split_annexb_frames(data)
    if not frames:
        print("[错误] 未找到有效的 HEVC 帧 (检查是否有 0x00000001 起始码)")
        return

    print(f"共 {len(frames)} 帧，目标: {host}:{port}")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    for frame_id, frame_data in enumerate(frames):
        total = len(frame_data)
        offset = 0
        frag_id = 0

        while offset < total:
            chunk = frame_data[offset:offset + frag_size]
            offset += len(chunk)

            # 构造头部: frame_id(2B) + frag_id(2B) + total_bytes(4B)
            header = struct.pack("!HHI", frame_id, frag_id, total)
            packet = header + chunk

            sock.sendto(packet, (host, port))
            frag_id += 1

        print(f"  帧 #{frame_id}: {total} bytes, {frag_id} 分片")
        time.sleep(0.04)  # ~25 fps

    sock.close()
    print(f"发送完成: {len(frames)} 帧")


def generate_and_send(host: str, port: int, frag_size: int = 1400):
    """用 ffmpeg 生成 HEVC 测试流并通过管道发送。"""
    import os
    import tempfile

    print("正在用 ffmpeg 生成测试 HEVC 流...")
    with tempfile.NamedTemporaryFile(suffix=".hevc", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        subprocess.run([
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=300x300:rate=25:duration=5",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
            "-c:v", "libx265", "-preset", "ultrafast", "-tune", "zerolatency",
            "-pix_fmt", "yuv420p", "-x265-params", "keyint=25:min-keyint=25",
            "-c:a", "aac", "-shortest",
            "-f", "hevc", tmp_path
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        file_size = os.path.getsize(tmp_path)
        print(f"生成 HEVC 文件: {tmp_path} ({file_size} bytes)")
        send_hevc_file(tmp_path, host, port, frag_size)
    finally:
        os.unlink(tmp_path)


def split_annexb_frames(data: bytes) -> list[bytes]:
    """按 Annex-B 4-byte start code (0x00000001) 切分帧。
    第一个起始码之前的数据跳过，两个起始码之间的数据为一个帧。
    """
    frames = []
    start_code = b"\x00\x00\x00\x01"
    positions = []
    pos = 0
    while True:
        idx = data.find(start_code, pos)
        if idx == -1:
            break
        positions.append(idx)
        pos = idx + 4

    for i in range(len(positions)):
        start = positions[i]
        end = positions[i + 1] if i + 1 < len(positions) else len(data)
        frame = data[start:end]
        if len(frame) > 4:  # 跳过空的起始码
            frames.append(frame)

    return frames


def _config_path() -> str:
    """config.json 在项目根目录（本脚本位于 tools/ 下）。"""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")


def load_udp_config() -> dict:
    """从 config.json 读取 udp 段，作为默认值。"""
    try:
        import json
        with open(_config_path(), encoding="utf-8") as f:
            return json.load(f).get("udp", {})
    except Exception:
        return {}


def main():
    # 先读 config.json 的 udp 段，命令行参数覆盖
    cfg = load_udp_config()

    parser = argparse.ArgumentParser(
        description="UDP HEVC 视频流发送测试工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 发送 HEVC 文件 (host/port 默认从 config.json 的 udp 段读取)
  python udp_test_sender.py --file input.hevc

  # 自动生成并发送测试流 (需要 ffmpeg)
  python udp_test_sender.py --generate

  # 命令行覆盖
  python udp_test_sender.py --generate --host 192.168.1.10 --port 3334
        """,
    )
    parser.add_argument("--file", help="HEVC Annex-B 文件路径")
    parser.add_argument("--generate", action="store_true",
                        help="用 ffmpeg 自动生成测试 HEVC 流")
    parser.add_argument("--host", default=None,
                        help=f"目标主机 (默认: {cfg.get('host', '127.0.0.1')})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"目标端口 (默认: {cfg.get('port', 3334)})")
    parser.add_argument("--frag-size", type=int, default=1400,
                        help="每个 UDP 分片最大字节数 (默认: 1400)")

    args = parser.parse_args()

    host = args.host if args.host is not None else cfg.get("host", "127.0.0.1")
    port = args.port if args.port is not None else cfg.get("port", 3334)

    if args.generate:
        generate_and_send(host, port, args.frag_size)
    elif args.file:
        send_hevc_file(args.file, host, port, args.frag_size)
    else:
        print("[提示] 请指定 --file 或 --generate")
        print()
        # 演示模式：手动构造一个最简 HEVC 帧发送
        print("发送演示包 (手动构造 HEVC Annex-B 数据)...")
        demo_send(host, port, args.frag_size)


def demo_send(host: str, port: int, frag_size: int):
    """演示：发送几个手动的空帧 (仅用于测试包格式解析，解码器不会产生图像)。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # HEVC Annex-B: VPS + SPS + PPS + IDR (简单的测试模式)
    # 这些是手工构造的最小参数集 (仅供测试包收发，不会解码出有效画面)
    demo_frames = [
        # VPS (NAL type 32)
        b"\x00\x00\x00\x01\x40\x01\x0c\x01\xff\xff\x01\x60\x00\x00\x03\x00\xb0\x00\x00\x03\x00\x00\x03\x00\x5d",
        # SPS (NAL type 33)
        b"\x00\x00\x00\x01\x42\x01\x01\x01\x60\x00\x00\x03\x00\xb0\x00\x00\x03\x00\x00\x03\x00\x5d\xa0\x02\x80\x80\x2d\x16\x59\x59\xa4\x93\x2b\xc0\x40\x40\x00\x00\xfa\x00\x00\x1d\x4c\x10",
        # PPS (NAL type 34)
        b"\x00\x00\x00\x01\x44\x01\xc0\x72\xb0\x62\x40",
    ]

    for frame_id, frame_data in enumerate(demo_frames):
        total = len(frame_data)
        header = struct.pack("!HHI", frame_id, 0, total)
        packet = header + frame_data
        sock.sendto(packet, (host, port))
        print(f"  帧 #{frame_id}: {total} bytes, 1 分片 → {host}:{port}")
        time.sleep(0.1)

    sock.close()
    print("演示包发送完成 (这些是参数集数据，不会产生画面)")
    print("提示: 用 --file 发送真实 HEVC 文件 或 --generate 自动生成测试流")


if __name__ == "__main__":
    main()
