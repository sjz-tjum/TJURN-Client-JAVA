# MQTT H.264 视频接收器 (Java 版)

本项目是 PyQt5 版 MQTT H.264 低带宽实时图传接收端的 Java 移植版，功能等价：
通过 MQTT 订阅 H.264 分片，重组码流，硬/软解码后在 Swing 窗口中实时显示，并提供实时统计。

## 功能

- MQTT 连接 / 断开，客户端 ID 可输入或自动生成
- 订阅并解析 `<Hq290s`（little-endian: seqId `uint16` + timestamp `int64` + 290 字节分片）
- Annex-B 码流重组，提取 SPS/PPS 构建 avcC
- H.264 解码（JavaCV / FFmpeg）
- Swing 实时视频显示 + 日志 + 统计（包数 / 帧数 / 解码 FPS / 显示 FPS / 丢包）

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

## 构建与运行

需要 JDK 17+ 和 Maven。

```bash
cd javaclient
mvn package                       # 生成 target/mqtt-h264-client.jar
java -jar target/mqtt-h264-client.jar
# 或直接运行
mvn exec:java
```

## 测试

```bash
mvn test
```

覆盖 `VideoPacketParser`（struct 解包、无符号 seqId、长度校验）与
`AvccExtractor`（SPS/PPS 提取、avcC 构建、起始码检测）。

## 配置

MQTT broker、端口、主题、渲染间隔等见 `com.mqttclient.config.Constants`。
