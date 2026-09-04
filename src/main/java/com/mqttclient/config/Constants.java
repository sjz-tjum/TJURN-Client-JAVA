package com.mqttclient.config;

/**
 * Global configuration constants.
 *
 * <p>All runtime parameters are loaded from {@code config.json} (see {@link Config}); after
 * editing the JSON, hot-reload via {@link #reload()} without recompiling.
 *
 * <p>Packet-format constants (fixed by the protocol) remain hard-coded.
 */
public final class Constants {

    private Constants() {
    }

    // ==== Packet format (fixed by the protocol, not read from config) ====
    /** Total bytes per packet */
    public static final int PACKET_SIZE = 300;
    /** Header length */
    public static final int HEADER_SIZE = 10;
    /** Payload length = 300 - 10 = 290 bytes */
    public static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;

    // ==== VideoStreamData struct ====
    /** seq_id(uint16) + timestamp(int64) + data(290 bytes) = 300 bytes */
    public static final int VIDEO_DATA_SIZE = 2 + 8 + 290;
    /** H.264 chunk length */
    public static final int H264_CHUNK_SIZE = 290;

    // ==== MQTT config (config.json: mqtt) ====
    public static String DEFAULT_BROKER_HOST = "127.0.0.1";
    public static int DEFAULT_BROKER_PORT = 1883;
    public static String DEFAULT_LOCAL_IP = "127.0.0.1";
    public static int MQTT_KEEPALIVE_SECONDS = 60;
    public static int MESSAGE_QUEUE_CAPACITY = 500;

    // ==== UDP config (config.json: udp) ====
    public static String UDP_HOST = "127.0.0.1";
    public static int UDP_PORT = 3334;
    public static int UDP_RECV_BUF_SIZE = 1_048_576;
    public static int UDP_FRAME_TIMEOUT_MS = 5000;
    public static int UDP_SO_TIMEOUT_MS = 500;

    // ==== Marker broadcast (config.json: ping) ====
    /** Direct client-to-client (UDP broadcast) marker port. */
    public static int PING_PORT = 3335;
    /** LAN broadcast address. */
    public static String PING_BROADCAST_ADDR = "255.255.255.255";

    // ==== Topics (config.json: topics / mqtt.topics) ====
    public static String ROBOT_POSITION_TOPIC = "RobotPosition";
    public static String ROBOT_STATIC_STATUS_TOPIC = "RobotStaticStatus";
    public static String ROBOT_DYNAMIC_STATUS_TOPIC = "RobotDynamicStatus";
    public static String VIDEO_TOPIC = "CustomByteBlock";
    public static String[] SUBSCRIBE_TOPICS = { "CustomByteBlock", "/video/#",
            "RobotPosition", "RobotStaticStatus", "RobotDynamicStatus" };
    public static int QOS = 0;

    // ==== Video parameters (config.json: video) ====
    public static int VIDEO_WIDTH = 300;
    public static int VIDEO_HEIGHT = 300;
    public static double DISPLAY_SCALE = 0.5;

    // ==== Buffering and decoding (config.json: buffer) ====
    /** Soft cap for the stream buffer: beyond it, truncate to the latest IDR frame */
    public static int STREAM_BUFFER_SOFT_LIMIT = 5 * 1024;
    /** Hard cap when no start code is found, to prevent memory leaks */
    public static int STREAM_BUFFER_HARD_LIMIT = 500_000;
    /** Queue backlog drop threshold */
    public static int QUEUE_BACKLOG_DROP = 5;
    /** Number of decoder threads */
    public static int DECODER_THREAD_COUNT = 8;
    /** Render timer interval in milliseconds */
    public static int RENDER_INTERVAL_MS = 2;

    static {
        reload();
    }

    /** Reloads all runtime parameters from config.json (hot-reload entry point). */
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
