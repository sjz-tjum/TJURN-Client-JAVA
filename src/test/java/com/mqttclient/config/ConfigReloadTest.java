package com.mqttclient.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * config.json 加载与热重载测试。
 *
 * <p>通过系统属性 {@code -Dmqtt.config} 指向临时配置，验证：
 * <ol>
 *   <li>启动时从 JSON 正确读取各类参数</li>
 *   <li>运行时改写 JSON 后 {@link Constants#reload()} 生效</li>
 * </ol>
 */
class ConfigReloadTest {

    @AfterEach
    void restoreDefaultConfig() {
        System.clearProperty("mqtt.config");
        Constants.reload();  // 恢复为项目 config.json 的默认值
    }

    @Test
    void loadsValuesFromJson() throws Exception {
        Path cfg = Files.createTempFile("mqtt-cfg", ".json");
        Files.writeString(cfg, """
                {
                  "mqtt": {
                    "host": "10.0.0.9", "port": 1999,
                    "topics": ["CustomByteBlock", "RobotPosition"]
                  },
                  "udp": { "host": "10.0.0.9", "port": 4444 },
                  "video": { "width": 640, "height": 480, "displayScale": 1.0 },
                  "buffer": { "streamBufferSoftLimit": 999, "decoderThreadCount": 4 }
                }
                """);
        System.setProperty("mqtt.config", cfg.toString());
        Constants.reload();

        // MQTT
        assertEquals("10.0.0.9", Constants.DEFAULT_BROKER_HOST);
        assertEquals(1999, Constants.DEFAULT_BROKER_PORT);
        assertArrayEquals(new String[]{"CustomByteBlock", "RobotPosition"},
                Constants.SUBSCRIBE_TOPICS);
        // UDP
        assertEquals(4444, Constants.UDP_PORT);
        assertEquals("10.0.0.9", Constants.UDP_HOST);
        // 视频
        assertEquals(640, Constants.VIDEO_WIDTH);
        assertEquals(480, Constants.VIDEO_HEIGHT);
        assertEquals(1.0, Constants.DISPLAY_SCALE);
        // 缓冲
        assertEquals(999, Constants.STREAM_BUFFER_SOFT_LIMIT);
        assertEquals(4, Constants.DECODER_THREAD_COUNT);
    }

    @Test
    void hotReloadAfterFileChange() throws Exception {
        Path cfg = Files.createTempFile("mqtt-cfg", ".json");
        Files.writeString(cfg, """
                {
                  "mqtt": { "host": "10.0.0.9", "port": 1999, "topics": ["CustomByteBlock"] },
                  "udp": { "host": "10.0.0.9", "port": 4444 },
                  "video": { "width": 640, "height": 480, "displayScale": 1.0 },
                  "buffer": { "streamBufferSoftLimit": 999, "decoderThreadCount": 4 }
                }
                """);
        System.setProperty("mqtt.config", cfg.toString());
        Constants.reload();

        // 模拟运行时编辑 config.json
        Files.writeString(cfg, """
                {
                  "mqtt": { "host": "10.0.0.10", "port": 2000, "topics": ["CustomByteBlock"] },
                  "udp": { "host": "10.0.0.10", "port": 5555 },
                  "video": { "width": 320, "height": 240, "displayScale": 0.5 },
                  "buffer": { "streamBufferSoftLimit": 111, "decoderThreadCount": 2 }
                }
                """);
        Constants.reload();

        assertEquals("10.0.0.10", Constants.DEFAULT_BROKER_HOST);
        assertEquals(2000, Constants.DEFAULT_BROKER_PORT);
        assertEquals(5555, Constants.UDP_PORT);
        assertEquals(320, Constants.VIDEO_WIDTH);
        assertEquals(240, Constants.VIDEO_HEIGHT);
        assertEquals(111, Constants.STREAM_BUFFER_SOFT_LIMIT);
        assertEquals(2, Constants.DECODER_THREAD_COUNT);
    }

    @Test
    void missingFileKeepsLastGoodValues() throws Exception {
        // 先加载一个已知配置
        Path cfg = Files.createTempFile("mqtt-cfg", ".json");
        Files.writeString(cfg, """
                { "mqtt": { "host": "10.0.0.9", "port": 1999, "topics": ["CustomByteBlock"] },
                  "udp": { "host": "10.0.0.9", "port": 4444 },
                  "video": { "width": 640, "height": 480 } }
                """);
        System.setProperty("mqtt.config", cfg.toString());
        Constants.reload();
        assertEquals(1999, Constants.DEFAULT_BROKER_PORT);

        // 切换到不存在的文件：reload 失败时应保持上次有效值，不抛异常
        System.setProperty("mqtt.config", "nonexistent_path_xyz.json");
        Constants.reload();
        assertEquals(1999, Constants.DEFAULT_BROKER_PORT);
        assertEquals(4444, Constants.UDP_PORT);
        assertEquals(640, Constants.VIDEO_WIDTH);
    }
}
