package com.mqttclient.config;

/**
 * 全局配置常量。
 * 对应 Python 版 utils/constants.py 以及散落在 processor_thread.py / main_window.py 中的常量。
 */
public final class Constants {

    private Constants() {
    }

    // ==== 包格式 ====
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

    // ==== 视频参数 ====
    public static final int VIDEO_WIDTH = 300;
    public static final int VIDEO_HEIGHT = 300;
    public static final double DISPLAY_SCALE = 0.5;

    // ==== MQTT 配置 (对应 main_window.py) ====
    // public static final String DEFAULT_BROKER_HOST = "192.168.12.1";
    // public static final int DEFAULT_BROKER_PORT = 3333;
    public static final String DEFAULT_BROKER_HOST = "broker.emqx.io";
    public static final int DEFAULT_BROKER_PORT = 1883;
    public static final String DEFAULT_LOCAL_IP = "192.168.12.2";
    public static final int MQTT_KEEPALIVE_SECONDS = 60;
    public static final int MESSAGE_QUEUE_CAPACITY = 500;

    /** 订阅主题 */
    public static final String ROBOT_POSITION_TOPIC = "RobotPosition";
    public static final String ROBOT_STATIC_STATUS_TOPIC = "RobotStaticStatus";
    public static final String ROBOT_DYNAMIC_STATUS_TOPIC = "RobotDynamicStatus";
    public static final String VIDEO_TOPIC = "CustomByteBlock";
    public static final String[] SUBSCRIBE_TOPICS = { VIDEO_TOPIC, "/video/#", ROBOT_POSITION_TOPIC,
            ROBOT_STATIC_STATUS_TOPIC, ROBOT_DYNAMIC_STATUS_TOPIC};
    public static final int QOS = 0;

    // ==== 缓冲区与解码 ====
    /** 流缓冲软上限：超过则截断到最新 IDR (对应 processor_thread.py 的 5 * 1024) */
    public static final int STREAM_BUFFER_SOFT_LIMIT = 5 * 1024;
    /** 无起始码时的硬上限，防止内存泄漏 (对应 h264_decoder.py 的 500000) */
    public static final int STREAM_BUFFER_HARD_LIMIT = 500_000;
    /** 队列积压丢弃阈值 (对应 processor_thread.py 的 qsize() > 5) */
    public static final int QUEUE_BACKLOG_DROP = 5;
    /** 解码线程数 (对应 h264_decoder.py 的 thread_count = 8) */
    public static final int DECODER_THREAD_COUNT = 8;
    /** 渲染定时器间隔毫秒 (对应 QTimer.start(2)) */
    public static final int RENDER_INTERVAL_MS = 2;
}
