package com.mqttclient.mqtt;

import com.mqttclient.config.Constants;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiConsumer;

/**
 * MQTT 接收器封装。
 *
 * <p>对应 Python 版 mqtt_client/client.py 的 MQTTReceiver：
 * <ul>
 *   <li>使用 Eclipse Paho 客户端，网络接收在独立线程中运行</li>
 *   <li>收到的消息 payload 放入有界队列，供处理线程消费</li>
 *   <li>队列满时丢弃最旧的一条消息（对应 put_nowait + 丢弃逻辑）</li>
 * </ul>
 */
public class MqttReceiver implements MqttCallback {

    private final String brokerHost;
    private final int brokerPort;
    private final String clientId;

    /** 线程安全的有界消息队列，存放原始 payload (对应 queue.Queue(maxsize=500)) */
    private final BlockingQueue<byte[]> messageQueue =
            new ArrayBlockingQueue<>(Constants.MESSAGE_QUEUE_CAPACITY);

    private MqttAsyncClient client;
    private volatile boolean connected = false;

    /** 连接状态变化回调 (connected, message)，对应 connection_status 信号 */
    private BiConsumer<Boolean, String> connectionStatusListener;

    /** 机器人位置消息回调（收到 {@link Constants#ROBOT_POSITION_TOPIC} 主题的原始 payload）。 */
    private java.util.function.Consumer<byte[]> robotPositionListener;

    public MqttReceiver(String brokerHost, int brokerPort, String clientId) {
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.clientId = clientId;
    }

    public void setConnectionStatusListener(BiConsumer<Boolean, String> listener) {
        this.connectionStatusListener = listener;
    }
                                                                  
    /** 注册机器人位置监听器；收到位置主题消息时以原始 payload 回调（在 Paho 网络线程执行）。 */
    public void setRobotPositionListener(java.util.function.Consumer<byte[]> listener) {
        this.robotPositionListener = listener;
    }

    public BlockingQueue<byte[]> getMessageQueue() {
        return messageQueue;
    }

    public boolean isConnected() {
        return connected;
    }

    /** 连接到 MQTT 服务器。对应 connect() + loop_start()。 */
    public boolean connect() {
        try {
            String serverUri = "tcp://" + brokerHost + ":" + brokerPort;
            System.out.printf("[MQTT] 正在连接 %s...%n", serverUri);
            client = new MqttAsyncClient(serverUri, clientId, new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setKeepAliveInterval(Constants.MQTT_KEEPALIVE_SECONDS);
            options.setAutomaticReconnect(false);

            client.connect(options, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                @Override
                public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken) {
                    connected = true;
                    System.out.printf("[MQTT] 连接成功: %s:%d%n", brokerHost, brokerPort);
                    notifyStatus(true, "连接成功");
                    // 连接成功后自动订阅
                    for (String topic : Constants.SUBSCRIBE_TOPICS) {
                        subscribe(topic, Constants.QOS);
                    }
                }

                @Override
                public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken, Throwable e) {
                    connected = false;
                    System.out.printf("[MQTT] 连接失败: %s%n", e.getMessage());
                    notifyStatus(false, "连接失败: " + e.getMessage());
                }
            });
            return true;
        } catch (MqttException e) {
            System.out.printf("[MQTT] 连接异常: %s%n", e.getMessage());
            notifyStatus(false, "连接异常: " + e.getMessage());
            return false;
        }
    }

    /** 断开连接。对应 disconnect() + loop_stop()。 */
    public void disconnect() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
            connected = false;
            System.out.println("[MQTT] 已断开连接");
        } catch (MqttException e) {
            System.out.printf("[MQTT] 断开连接异常: %s%n", e.getMessage());
        }
    }

    public boolean subscribe(String topic, int qos) {
        if (!connected) {
            System.out.printf("[MQTT] 未连接，无法订阅: %s%n", topic);
            return false;
        }
        try {
            client.subscribe(topic, qos);
            System.out.printf("[MQTT] 已订阅: %s (qos=%d)%n", topic, qos);
            return true;
        } catch (MqttException e) {
            System.out.printf("[MQTT] 订阅失败 %s: %s%n", topic, e.getMessage());
            return false;
        }
    }

    public void unsubscribe(String topic) {
        try {
            if (client != null) {
                client.unsubscribe(topic);
                System.out.printf("[MQTT] 已取消订阅: %s%n", topic);
            }
        } catch (MqttException e) {
            System.out.printf("[MQTT] 取消订阅失败: %s%n", e.getMessage());
        }
    }

    // ==== MqttCallback ====

    @Override
    public void connectionLost(Throwable cause) {
        connected = false;
        System.out.printf("[MQTT] 连接断开: %s%n", cause == null ? "unknown" : cause.getMessage());
        notifyStatus(false, "连接已断开");
    }

    /**
     * 消息到达回调（在 Paho 网络线程中执行）。
     * 仅做最轻量的入队操作，队列满时丢弃最旧消息。
     * 对应 Python 的 _on_message + put_nowait 逻辑。
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        byte[] payload = message.getPayload();
        // 机器人位置走独立回调，不进入视频队列
        if (Constants.ROBOT_POSITION_TOPIC.equals(topic)) {
            if (robotPositionListener != null) {
                try {
                    robotPositionListener.accept(payload);
                } catch (Exception e) {
                    System.out.printf("[MQTT] 机器人位置回调异常: %s%n", e.getMessage());
                }
            }
            return;
        }
        if (!messageQueue.offer(payload)) {
            // 队列满，丢弃最旧的一条再放入新消息
            messageQueue.poll();
            messageQueue.offer(payload);
            System.out.println("[MQTT] 警告: 消息队列已满，丢弃旧消息");
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }

    private void notifyStatus(boolean isConnected, String message) {
        if (connectionStatusListener != null) {
            connectionStatusListener.accept(isConnected, message);
        }
    }
}
