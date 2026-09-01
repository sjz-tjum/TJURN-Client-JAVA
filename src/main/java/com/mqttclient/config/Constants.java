package com.mqttclient.config;

/**
 * 全局配置常量。
 *
 * <p>所有运行参数都从 {@code config.json} 加载（见 {@link Config}），
 * 修改 JSON 后通过 {@link #reload()} 热重载，无需重新编译。
 *
 * <p>包格式相关常量（协议固定）保持硬编码。
 */
public final class Constants {

    private Constants() {
    }

    // ==== 包格式（协议固定，不从配置读取）====
    /** 单包总字节数 */
    public static final int PACKET_SIZE = 300;
    /** 头部长度 */
    public static final int HEADER_SIZE = 10;
    /** 负载长度 = 300 - 10 = 290 字节 */
    public static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;

    // ==== VideoStreamData 结构体 ====
    /** seq_id(uint16) + timestamp(int64) + data(290 bytes) = 300 字节 */
    public static final int VIDEO_DATA_SIZE = 2 + 8 + 290;
    /** H.264 分片长度 */
    public static final int H264_CHUNK_SIZE = 290;

    // ==== MQTT 配置（config.json: mqtt）====
    public static String DEFAULT_BROKER_HOST = "127.0.0.1";
    public static int DEFAULT_BROKER_PORT = 1883;
    public static String DEFAULT_LOCAL_IP = "127.0.0.1";
    public static int MQTT_KEEPALIVE_SECONDS = 60;
    public static int MESSAGE_QUEUE_CAPACITY = 500;

    // ==== UDP 配置（config.json: udp）====
    public static String UDP_HOST = "127.0.0.1";
    public static int UDP_PORT = 3334;
    public static int UDP_RECV_BUF_SIZE = 1_048_576;
    public static int UDP_FRAME_TIMEOUT_MS = 5000;
    public static int UDP_SO_TIMEOUT_MS = 500;

    // ==== 标点广播（config.json: ping）====
    /** 客户端直连（UDP 广播）标点端口。 */
    public static int PING_PORT = 3335;
    /** 局域网广播地址。 */
    public static String PING_BROADCAST_ADDR = "255.255.255.255";

    // ==== 主题（config.json: topics / mqtt.topics）====
    public static String ROBOT_POSITION_TOPIC = "RobotPosition";
    public static String ROBOT_STATIC_STATUS_TOPIC = "RobotStaticStatus";
    public static String ROBOT_DYNAMIC_STATUS_TOPIC = "RobotDynamicStatus";
    public static String VIDEO_TOPIC = "CustomByteBlock";
    public static String[] SUBSCRIBE_TOPICS = { "CustomByteBlock", "/video/#",
            "RobotPosition", "RobotStaticStatus", "RobotDynamicStatus" };
    public static int QOS = 0;

    // ==== 视频参数（config.json: video）====
    public static int VIDEO_WIDTH = 300;
    public static int VIDEO_HEIGHT = 300;
    public static double DISPLAY_SCALE = 0.5;

    // ==== 缓冲区与解码（config.json: buffer）====
    /** 流缓冲软上限：超过则截断到最新 IDR */
    public static int STREAM_BUFFER_SOFT_LIMIT = 5 * 1024;
    /** 无起始码时的硬上限，防止内存泄漏 */
    public static int STREAM_BUFFER_HARD_LIMIT = 500_000;
    /** 队列积压丢弃阈值 */
    public static int QUEUE_BACKLOG_DROP = 5;
    /** 解码线程数 */
    public static int DECODER_THREAD_COUNT = 8;
    /** 渲染定时器间隔毫秒 */
    public static int RENDER_INTERVAL_MS = 2;

    static {
        reload();
    }

    /** 从 config.json 重新加载全部运行参数（热重载入口）。 */
    public static synchronized void reload() {
        Config.load(Config.CONFIG_FILE);

        // mqtt
        DEFAULT_BROKER_HOST = Config.str("mqtt", "host", DEFAULT_BROKER_HOST);
        DEFAULT_BROKER_PORT = Config.i("mqtt", "port", DEFAULT_BROKER_PORT);
        MQTT_KEEPALIVE_SECONDS = Config.i("mqtt", "keepAlive", MQTT_KEEPALIVE_SECONDS);
        MESSAGE_QUEUE_CAPACITY = Config.i("mqtt", "queueCapacity", MESSAGE_QUEUE_CAPACITY);
        QOS = Config.i("mqtt", "qos", QOS);
        SUBSCRIBE_TOPICS = Config.strArray("mqtt", "topics", SUBSCRIBE_TOPICS);

        // topics
        VIDEO_TOPIC = Config.str("topics", "video", VIDEO_TOPIC);
        ROBOT_POSITION_TOPIC = Config.str("topics", "robotPosition", ROBOT_POSITION_TOPIC);
        ROBOT_STATIC_STATUS_TOPIC = Config.str("topics", "robotStaticStatus", ROBOT_STATIC_STATUS_TOPIC);
        ROBOT_DYNAMIC_STATUS_TOPIC = Config.str("topics", "robotDynamicStatus", ROBOT_DYNAMIC_STATUS_TOPIC);

        // udp
        PING_PORT = Config.i("ping", "port", PING_PORT);
        PING_BROADCAST_ADDR = Config.str("ping", "broadcast", PING_BROADCAST_ADDR);
        UDP_HOST = Config.str("udp", "host", UDP_HOST);
        UDP_PORT = Config.i("udp", "port", UDP_PORT);
        UDP_RECV_BUF_SIZE = Config.i("udp", "recvBufferSize", UDP_RECV_BUF_SIZE);
        UDP_FRAME_TIMEOUT_MS = Config.i("udp", "frameTimeoutMs", UDP_FRAME_TIMEOUT_MS);
        UDP_SO_TIMEOUT_MS = Config.i("udp", "soTimeoutMs", UDP_SO_TIMEOUT_MS);

        // video
        VIDEO_WIDTH = Config.i("video", "width", VIDEO_WIDTH);
        VIDEO_HEIGHT = Config.i("video", "height", VIDEO_HEIGHT);
        DISPLAY_SCALE = Config.d("video", "displayScale", DISPLAY_SCALE);

        // buffer
        STREAM_BUFFER_SOFT_LIMIT = Config.i("buffer", "streamBufferSoftLimit", STREAM_BUFFER_SOFT_LIMIT);
        STREAM_BUFFER_HARD_LIMIT = Config.i("buffer", "streamBufferHardLimit", STREAM_BUFFER_HARD_LIMIT);
        QUEUE_BACKLOG_DROP = Config.i("buffer", "queueBacklogDrop", QUEUE_BACKLOG_DROP);
        DECODER_THREAD_COUNT = Config.i("buffer", "decoderThreadCount", DECODER_THREAD_COUNT);
        RENDER_INTERVAL_MS = Config.i("buffer", "renderIntervalMs", RENDER_INTERVAL_MS);
    }
}
