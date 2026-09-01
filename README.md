# MQTT H.264 视频接收器 (Java 版)

本项目是 PyQt5 版 MQTT H.264 低带宽实时图传接收端的 Java 移植版，功能等价：
通过 MQTT 订阅 H.264 分片，重组码流，硬/软解码后在 Swing 窗口中实时显示，并提供实时统计。

## 功能

- MQTT 连接 / 断开，客户端 ID 可输入或自动生成
- 订阅并解析 `<Hq290s`（little-endian: seqId `uint16` + timestamp `int64` + 290 字节分片）
- Annex-B 码流重组，提取 SPS/PPS 构建 avcC
- H.264 解码（JavaCV / FFmpeg）
- Swing 实时视频显示 + 日志 + 统计（包数 / 帧数 / 解码 FPS / 显示 FPS / 丢包）
- 小地图标点（ping）：点击小地图广播坐标，所有队友看到波纹动画 + 顶部横幅 + 屏幕闪光提醒

## 与 Python 版的对应关系

| Python 版 | Java 版 |
|-----------|---------|
| `main.py` | `com.mqttclient.Main` |
| `ui/main_window.py` | `com.mqttclient.ui.MainWindow` |
| `mqtt_client/client.py` | `com.mqttclient.mqtt.MqttReceiver` |
| `mqtt_client/protobuf_parser.py` | `com.mqttclient.protobuf.VideoPacketParser` |
| `video/h264_decoder.py` | `com.mqttclient.video.{H264Decoder, AvccExtractor, StreamBuffer}` |
| `video/processor_thread.py` | `com.mqttclient.video.VideoProcessor` |
| `utils/constants.py` | `com.mqttclient.config.Constants` |
| paho.mqtt / PyAV / protobuf | Eclipse Paho / JavaCV / protobuf-java |

## 扩展接口

`com.mqttclient.ext` 下预留了扩展点，便于在不改动核心的情况下添加功能：

- `PacketProcessor` — 在解包后、解码前对分片做处理（如统计、过滤、加密解密）
- `FrameListener` — 每解出一帧回调（如录制、转发、AI 推理）
- `StatsListener` — 统计信息回调（如上报监控）

`VideoProcessor` 提供 `addPacketProcessor` / `addFrameListener` / `setStatsListener` 注册这些扩展。

## 测试工具 (tools/)

| 脚本 | 作用 | 依赖 |
|------|------|------|
| `tools/mqtt_broker.py` | 纯 Python MQTT 3.1.1 Broker，本地转发 | 无 |
| `tools/mqtt_test_sender.py` | MP4 → H.264 → 290B 分片 → protobuf → 发布 MQTT | PyAV, paho-mqtt |
| `tools/udp_mp4_sender.py` | MP4 → Annex-B HEVC → UDP 分片发送 | PyAV |
| `tools/udp_test_sender.py` | 裸 Annex-B HEVC 文件 → UDP 分片发送 | 无（--generate 需 ffmpeg）|

安装依赖：

```bash
pip install av paho-mqtt
```

所有脚本从项目根目录的 `config.json` 读取默认参数（host/port 等），命令行参数优先。

## 构建与运行

需要 JDK 17+ 和 Maven。

```bash
cd javaclient
mvn compile exec:java     # 直接运行客户端
```

客户端按键：`0` 切换 UDP/MQTT 视频源，`1` 连接 MQTT，`3` 开始解码，`F5` 重载配置，`ESC` 控制菜单。

### UDP HEVC 视频流

客户端支持两种视频源，随时切换（按 `0` 或控制菜单"切换视频源"）：

| 视频源 | 传输 | 编码 |
|--------|------|------|
| MQTT | `CustomByteBlock` 主题 | H.264 (JavaCV) |
| UDP | 端口 3334 | HEVC / H.265 (JavaCV) |

UDP 包格式（big-endian）：

```
frame_id (2B) + frag_id (2B) + total_bytes (4B) + HEVC 数据
```

测试发送脚本都在 `tools/` 目录下：

```bash
# 方式一：发送 MP4 容器（PyAV 解封装 → 转 Annex-B → UDP）— 需 pip install av
python tools/udp_mp4_sender.py "视频.mp4"                    # 实时速度
python tools/udp_mp4_sender.py "视频.mp4" --speed 5          # 5 倍速

# 方式二：发送裸 Annex-B HEVC 文件（需 ffmpeg 或现成 .hevc 文件）
python tools/udp_test_sender.py --generate --host 127.0.0.1
python tools/udp_test_sender.py --file input.hevc --host 127.0.0.1
```

> MP4 里的 HEVC 参数集（VPS/SPS/PPS）通常只存在 hvcC 里、不在包流中，
> `udp_mp4_sender.py` 会自动从 hvcC 解析并拼到首帧/关键帧前。

### MQTT 视频流

```bash
# 1. 启动本地 broker
python tools/mqtt_broker.py [--host 0.0.0.0] [--port 11883] [--verbose]

# 2. 发送 H.264 视频到 MQTT（MP4 → libx264 转码 → 290B 分片 → protobuf → 发布）
python tools/mqtt_test_sender.py "视频.mp4"                    # 实时速度
python tools/mqtt_test_sender.py "视频.mp4" --speed 2         # 2 倍速
python tools/mqtt_test_sender.py "视频.mp4" --max-frames 60   # 只发前 60 帧（快速测试）
```

`mqtt_broker.py` 是纯 Python asyncio 实现的 MQTT 3.1.1 Broker（无外部依赖）。
MQTT 视频包格式（与 Java 客户端一致）：

```
MQTT 载荷 = CustomByteBlock protobuf { bytes data = VideoStreamData }
VideoStreamData = seq_id(uint16 LE) + timestamp(int64 LE) + 290 字节 H.264 分片
```

> 客户端 H.264 解码走**流内 SPS/PPS**（Annex-B），因此发送端必须提供带起始码的
> Annex-B 流，客户端才能重组解码。

### 小地图标点（Ping）

MOBA 风格的团队协作标点：**点击小地图上任意位置**，坐标通过 **UDP 广播**直接发给同一以太网内
的所有客户端（**无需 MQTT broker、无需 protobuf**）。每台客户端在小地图上显示
**霓虹青波纹扩散动画**，同时屏幕顶部弹出**科幻 HUD 横幅**（深色斜切角面板 + 霓虹发光边框 +
扫描线 + 发光「注意！」+ 霓虹警告三角），约 3 秒后淡出。

- 触发：小地图上**左键单击**（双击仍是全屏切换，已做防抖）
- 传输：UDP 广播到 `ping.port`（默认 3335），纯文本 `PING,x,y,sender`
- 标点者自己也会立即看到波纹（本地乐观回显）
- 相关类：`com.mqttclient.net.PingChannel`（UDP 广播收发）、`MinimapOverlay`（波纹 + 点击）、
  `PingAlertOverlay`（横幅 + 闪光）
- 端口/广播地址可在 `config.json` 的 `ping` 段配置

## 测试

```bash
mvn test
```

覆盖 `VideoPacketParser`（struct 解包、无符号 seqId、长度校验）、
`AvccExtractor`（SPS/PPS 提取、avcC 构建、起始码检测）、
`UdpFrameReassembler`（乱序重组、去重、超时清理）、
`ConfigReloadTest`（配置加载与热重载）、
`UdpProcessorEndToEndTest`（UDP 收包 + HEVC 解码端到端，缺编码器时自动跳过）。

## 配置（config.json 软编码）

所有运行参数从 `config.json` 读取（工作目录优先，其次 classpath，最后内置默认值）。
修改后保存即自动热重载（每 2 秒检测文件变化），也可按 `F5` 或控制菜单"重载配置"手动刷新。

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

> 端口 1883 在部分 Windows 上会被系统保留（Hyper-V），若绑定失败改用 11883。

- 也可用 `-Dmqtt.config=路径` 指定其他配置文件。
- 提示：MQTT 连接参数需重连生效；UDP/缓冲/分辨率参数需"停止再启动"生效。
- `broker` 段供 `mqtt_broker.py` 读取启动参数（命令行参数优先）。

对应 Java 类：`com.mqttclient.config.Config`（JSON 加载器）、`com.mqttclient.config.Constants`（参数表）。
