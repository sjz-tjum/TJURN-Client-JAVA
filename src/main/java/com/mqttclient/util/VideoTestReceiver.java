package com.mqttclient.util;

import com.mqttclient.config.Constants;
import com.mqttclient.mqtt.MqttReceiver;
import com.mqttclient.video.UdpVideoProcessor;
import com.mqttclient.video.VideoProcessor;

/**
 * 无头测试接收器：同时启动 MQTT(H.264) 与 UDP(HEVC) 两条解码链路，
 * 运行 N 秒后打印统计，供 {@code tools/run_test.sh} 一键测试使用。
 *
 * <p>输出格式（脚本解析用）：
 * <pre>
 *   [RESULT_MQTT] received=.. decoded=..
 *   [RESULT_UDP]  received=.. decoded=..
 * </pre>
 */
public class VideoTestReceiver {

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 12;

        // MQTT 链路
        MqttReceiver mqtt = new MqttReceiver(Constants.DEFAULT_BROKER_HOST,
                Constants.DEFAULT_BROKER_PORT, "testrecv_" + System.currentTimeMillis());
        mqtt.connect();
        VideoProcessor mqttProc = new VideoProcessor(mqtt);
        mqttProc.start();

        // UDP 链路
        UdpVideoProcessor udpProc = new UdpVideoProcessor(Constants.UDP_HOST);
        udpProc.start();

        Thread.sleep(seconds * 1000L);

        System.out.println("[RESULT_MQTT] received=" + mqttProc.getReceivedPackets()
                + " decoded=" + mqttProc.getDecodedFrames());
        System.out.println("[RESULT_UDP] received=" + udpProc.getReceivedPackets()
                + " decoded=" + udpProc.getDecodedFrames());

        mqttProc.stopProcessor();
        mqtt.disconnect();
        udpProc.stopProcessor();
        System.exit(0);
    }
}
