# MQTT H.264 Video Receiver (Java)

This project is a Java port of the PyQt5-based MQTT H.264 low-bandwidth real-time video receiver, with equivalent functionality:
it subscribes to fragmented H.264 over MQTT, reassembles the bitstream, decodes it with hardware/software codecs, and displays the
live feed in a Swing window together with real-time statistics.

## Features

- MQTT connect / disconnect; client ID can be entered manually or auto-generated
- Subscribe to and parse `<Hq290s` fragments (little-endian: seqId `uint16` + timestamp `int64` + 290-byte fragment)
- Annex-B bitstream reassembly; extracts SPS/PPS to build avcC
- H.264 decoding (JavaCV / FFmpeg)
- Swing real-time video display + logging + statistics (packet count / frame count / decode FPS / display FPS / packet loss)
- Minimap ping: click the minimap to broadcast coordinates; all teammates see a ripple animation + top banner + screen flash alert

## Mapping to the Python Version

| Python version | Java version |
|-----------|---------|
| `main.py` | `com.mqttclient.Main` |
| `ui/main_window.py` | `com.mqttclient.ui.MainWindow` |
| `mqtt_client/client.py` | `com.mqttclient.mqtt.MqttReceiver` |
| `mqtt_client/protobuf_parser.py` | `com.mqttclient.protobuf.VideoPacketParser` |
| `video/h264_decoder.py` | `com.mqttclient.video.{H264Decoder, AvccExtractor, StreamBuffer}` |
| `video/processor_thread.py` | `com.mqttclient.video.VideoProcessor` |
| `utils/constants.py` | `com.mqttclient.config.Constants` |
| paho.mqtt / PyAV / protobuf | Eclipse Paho / JavaCV / protobuf-java |

## Extension Interfaces

The `com.mqttclient.ext` package reserves extension points so features can be added without modifying the core:

- `PacketProcessor` — processes fragments after unpacking and before decoding (e.g., statistics, filtering, encryption/decryption)
- `FrameListener` — callback for each decoded frame (e.g., recording, forwarding, AI inference)
- `StatsListener` — statistics callback (e.g., monitoring/telemetry reporting)

`VideoProcessor` provides `addPacketProcessor` / `addFrameListener` / `setStatsListener` to register these extensions.

## Test Tools (tools/)

| Script | Purpose | Dependencies |
|------|------|------|
| `tools/mqtt_broker.py` | Pure-Python MQTT 3.1.1 broker for local forwarding | None |
| `tools/mqtt_test_sender.py` | MP4 → H.264 → 290B fragments → protobuf → publish to MQTT | PyAV, paho-mqtt |
| `tools/udp_mp4_sender.py` | MP4 → Annex-B HEVC → send as UDP fragments | PyAV |
| `tools/udp_test_sender.py` | Raw Annex-B HEVC file → send as UDP fragments | None (`--generate` needs ffmpeg) |
| `tools/run_test.sh` | **One-click test** of both the MQTT and UDP links (headless auto-verification) | Java/Maven + Python |

### One-Click Test

```bash
bash tools/run_test.sh            # test MQTT + UDP
bash tools/run_test.sh --mqtt     # test MQTT only
bash tools/run_test.sh --udp      # test UDP only
```

Flow: compile → start a local broker → start the headless receiver `VideoTestReceiver` (which decodes both MQTT H.264 and UDP HEVC) →
run `mqtt_test_sender.py` (128x128.264) and `udp_test_sender.py` (test_sample.hevc) → report ✅/❌ based on decoded frame counts.

Install dependencies:

```bash
pip install av paho-mqtt
```

All scripts read default parameters (host/port, etc.) from `config.json` in the project root; command-line arguments take precedence.

## Build & Run

Requires JDK 17+ and Maven.

```bash
cd javaclient
mvn compile exec:java     # run the client directly
```

Client key bindings: `0` switches the UDP/MQTT video source, `1` connects to MQTT, `3` starts decoding, `F5` reloads the config, `ESC` opens the control menu.

### UDP HEVC Video Stream

The client supports two video sources that can be switched at any time (press `0` or use "switch video source" in the control menu):

| Video source | Transport | Codec |
|--------|------|------|
| MQTT | `CustomByteBlock` topic | H.264 (JavaCV) |
| UDP | port 3334 | HEVC / H.265 (JavaCV) |

UDP packet format (big-endian):

```
frame_id (2B) + frag_id (2B) + total_bytes (4B) + HEVC data
```

Test sender scripts are under `tools/`:

```bash
# Option 1: send an MP4 container (PyAV demux → Annex-B → UDP) — requires pip install av
python tools/udp_mp4_sender.py "video.mp4"                    # real-time speed
python tools/udp_mp4_sender.py "video.mp4" --speed 5          # 5x speed

# Option 2: send a raw Annex-B HEVC file (needs ffmpeg or an existing .hevc file)
python tools/udp_test_sender.py --generate --host 127.0.0.1
python tools/udp_test_sender.py --file input.hevc --host 127.0.0.1
```

> HEVC parameter sets (VPS/SPS/PPS) in an MP4 usually exist only inside hvcC, not in the packet stream;
> `udp_mp4_sender.py` automatically parses them from hvcC and prepends them to the first frame/key frames.

### MQTT Video Stream

```bash
# 1. Start a local broker
python tools/mqtt_broker.py [--host 0.0.0.0] [--port 11883] [--verbose]

# 2. Send H.264 video over MQTT (MP4 → libx264 transcode → 290B fragments → protobuf → publish)
python tools/mqtt_test_sender.py "video.mp4"                    # real-time speed
python tools/mqtt_test_sender.py "video.mp4" --speed 2         # 2x speed
python tools/mqtt_test_sender.py "video.mp4" --max-frames 60   # send only the first 60 frames (quick test)
```

`mqtt_broker.py` is a pure-Python asyncio MQTT 3.1.1 broker (no external dependencies).
MQTT video packet format (identical to the Java client):

```
MQTT payload = CustomByteBlock protobuf { bytes data = VideoStreamData }
VideoStreamData = seq_id(uint16 LE) + timestamp(int64 LE) + 290-byte H.264 fragment
```

> The client's H.264 decoding relies on **in-stream SPS/PPS** (Annex-B), so the sender must supply an
> Annex-B stream with start codes for the client to reassemble and decode.

### Minimap Ping

MOBA-style team coordination pings: **click anywhere on the minimap**, and the coordinates are sent directly to all
clients on the same Ethernet network via **UDP broadcast** (**no MQTT broker, no protobuf needed**). Each client shows a
**neon-cyan ripple spread animation** on the minimap, while a **sci-fi HUD banner** slides in at the top of the screen
(dark beveled panel + neon glow border + scanlines + a glowing "注意！" ("Attention!") label + neon warning triangle), fading out after ~3 seconds.

- Trigger: **left-click** on the minimap (double-click still toggles fullscreen, debounced)
- Transport: UDP broadcast to `ping.port` (default 3335), plain-text `PING,x,y,sender`
- The ping sender also sees the ripple immediately (local optimistic echo)
- Related classes: `com.mqttclient.net.PingChannel` (UDP broadcast send/receive), `MinimapOverlay` (ripple + click),
  `PingAlertOverlay` (banner + flash)
- Port/broadcast address can be configured in the `ping` section of `config.json`

## Tests

```bash
mvn test
```

Covers `VideoPacketParser` (struct unpacking, unsigned seqId, length validation),
`AvccExtractor` (SPS/PPS extraction, avcC building, start-code detection),
`UdpFrameReassembler` (out-of-order reassembly, deduplication, timeout cleanup),
`ConfigReloadTest` (config loading and hot reload), and
`UdpProcessorEndToEndTest` (UDP receive + HEVC decode end-to-end, auto-skipped when no encoder is available).

## Configuration (config.json Soft-Coding)

All runtime parameters are read from `config.json` (working directory first, then classpath, then built-in defaults).
Saving changes triggers automatic hot reload (file changes detected every 2 seconds); you can also press `F5` or use
"reload config" in the control menu to refresh manually.

```json
{
  "mqtt":   { "host": "127.0.0.1", "port": 11883, "keepAlive": 60, "qos": 0, "topics": [...] },
  "topics": { "video": "CustomByteBlock", "robotPosition": "RobotPosition", ... },
  "udp":    { "host": "127.0.0.1", "port": 3334, "recvBufferSize": 1048576,
              "frameTimeoutMs": 5000, "soTimeoutMs": 500 },
  "ping":   { "port": 3335, "broadcast": "255.255.255.255" },
  "video":  { "width": 300, "height": 300, "displayScale": 0.5 },
  "buffer": { "streamBufferSoftLimit": 5120, "streamBufferHardLimit": 500000,
              "queueBacklogDrop": 5, "decoderThreadCount": 8, "renderIntervalMs": 2 },
  "broker": { "host": "0.0.0.0", "port": 11883, "verbose": false }
}
```

> Port 1883 is reserved on some Windows systems (Hyper-V); if binding fails, use 11883 instead.

- You can also specify another config file with `-Dmqtt.config=path`.
- Note: MQTT connection parameters take effect after reconnecting; UDP/buffer/resolution parameters take effect after "stop and restart".
- The `broker` section is read by `mqtt_broker.py` for startup parameters (command-line arguments take precedence).

Corresponding Java classes: `com.mqttclient.config.Config` (JSON loader) and `com.mqttclient.config.Constants` (parameter table).
