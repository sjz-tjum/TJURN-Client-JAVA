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
 * MQTT receiver wrapper.
 *
 * <p>Corresponds to MQTTReceiver in the Python version (mqtt_client/client.py):
 * <ul>
 *   <li>Uses the Eclipse Paho client; network reception runs on a dedicated thread</li>
 *   <li>Incoming message payloads go into a bounded queue consumed by the processing thread</li>
 *   <li>When the queue is full, drops the oldest message (mirrors the put_nowait + drop logic)</li>
 * </ul>
 */
public class MqttReceiver implements MqttCallback {

    private final String brokerHost;
    private final int brokerPort;
    private final String clientId;

    /** Thread-safe bounded message queue holding raw payloads (mirrors queue.Queue(maxsize=500)) */
    private final BlockingQueue<byte[]> messageQueue =
            new ArrayBlockingQueue<>(Constants.MESSAGE_QUEUE_CAPACITY);

    private MqttAsyncClient client;
    private volatile boolean connected = false;

    /** Connection-state change callback (connected, message); mirrors the connection_status signal */
    private BiConsumer<Boolean, String> connectionStatusListener;

    /** Robot-position message callback (raw payload for {@link Constants#ROBOT_POSITION_TOPIC}). */
    private java.util.function.Consumer<byte[]> robotPositionListener;

    /** Robot static-status callback (robot_type / level / max_health / max_heat). */
    private java.util.function.Consumer<byte[]> robotStaticStatusListener;

    /** Robot dynamic-status callback (current_health / current_heat / current_experience / experience_for_upgrade). */
    private java.util.function.Consumer<byte[]> robotDynamicStatusListener;

    public MqttReceiver(String brokerHost, int brokerPort, String clientId) {
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.clientId = clientId;
    }

    public void setConnectionStatusListener(BiConsumer<Boolean, String> listener) {
        this.connectionStatusListener = listener;
    }
                                                                  
    /** Registers the robot-position listener; invoked with the raw payload when a position message arrives (runs on the Paho network thread). */
    public void setRobotPositionListener(java.util.function.Consumer<byte[]> listener) {
        this.robotPositionListener = listener;
    }

    /** Registers the robot static-status listener. */
    public void setRobotStaticStatusListener(java.util.function.Consumer<byte[]> listener) {
        this.robotStaticStatusListener = listener;
    }

    /** Registers the robot dynamic-status listener. */
    public void setRobotDynamicStatusListener(java.util.function.Consumer<byte[]> listener) {
        this.robotDynamicStatusListener = listener;
    }

    public BlockingQueue<byte[]> getMessageQueue() {
        return messageQueue;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Connects to the MQTT broker. Mirrors connect() + loop_start(). */
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
                    // Auto-subscribe once connected
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

    /** Disconnects. Mirrors disconnect() + loop_stop(). */
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

    /** Publishes a message to the given topic. */
    public void publish(String topic, byte[] payload, int qos) {
        try {
            if (client == null || !client.isConnected()) {
                System.out.printf("[MQTT] 未连接，无法发布到: %s%n", topic);
                return;
            }
            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(qos);
            msg.setRetained(false);
            client.publish(topic, msg);
        } catch (MqttException e) {
            System.out.printf("[MQTT] 发布失败 %s: %s%n", topic, e.getMessage());
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
     * Message-arrival callback (runs on the Paho network thread).
     * Only enqueues as cheaply as possible, dropping the oldest message when the queue is full.
     * Mirrors Python's _on_message + put_nowait logic.
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        byte[] payload = message.getPayload();
        // Robot status uses its own callbacks, not the video queue
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
        if (Constants.ROBOT_STATIC_STATUS_TOPIC.equals(topic)) {
            if (robotStaticStatusListener != null) {
                try {
                    robotStaticStatusListener.accept(payload);
                } catch (Exception e) {
                    System.out.printf("[MQTT] 机器人静态状态回调异常: %s%n", e.getMessage());
                }
            }
            return;
        }
        if (Constants.ROBOT_DYNAMIC_STATUS_TOPIC.equals(topic)) {
            if (robotDynamicStatusListener != null) {
                try {
                    robotDynamicStatusListener.accept(payload);
                } catch (Exception e) {
                    System.out.printf("[MQTT] 机器人动态状态回调异常: %s%n", e.getMessage());
                }
            }
            return;
        }
        if (!messageQueue.offer(payload)) {
            // Queue is full: drop the oldest message, then insert the new one
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
