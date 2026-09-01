package com.mqttclient.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 客户端直连的标点广播通道（UDP 广播，无 broker、无 protobuf）。
 *
 * <p>同一以太网内的所有客户端监听固定 UDP 端口；某台标点时向局域网广播地址发送一条
 * 简单文本消息 {@code PING,x,y,sender}，其余客户端（含发送者自己）都能收到。
 *
 * <p>消息格式（纯文本，逗号分隔）：
 * <pre>
 *   PING,12.5,7.25,clientId
 * </pre>
 */
public class PingChannel implements AutoCloseable {

    /** 收到的标点消息。 */
    public record PingMessage(float x, float y, String sender) {
    }

    private final int port;
    private final String broadcastAddr;
    private final Consumer<PingMessage> listener;

    private volatile boolean running = true;
    private DatagramSocket socket;
    private Thread receiveThread;

    /**
     * @param port          监听/广播端口（同一局域网内所有客户端一致）
     * @param broadcastAddr 局域网广播地址，如 {@code 255.255.255.255}
     * @param listener      收到标点时的回调（在接收线程执行）
     */
    public PingChannel(int port, String broadcastAddr, Consumer<PingMessage> listener) {
        this.port = port;
        this.broadcastAddr = broadcastAddr;
        this.listener = listener;
    }

    /** 开始监听广播（独立接收线程）。 */
    public void start() {
        try {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
            socket.setSoTimeout(200);   // 周期性醒来检查 running
            receiveThread = new Thread(this::receiveLoop, "PingChannel");
            receiveThread.setDaemon(true);
            receiveThread.start();
            System.out.println("[Ping] 监听 UDP " + port + " 广播标点");
        } catch (SocketException e) {
            System.err.println("[Ping] 监听失败: " + e.getMessage());
        }
    }

    /**
     * 广播一条标点。
     *
     * @param x      世界坐标 X（米）
     * @param y      世界坐标 Y（米）
     * @param sender 发送者客户端 ID
     */
    public void broadcastPing(float x, float y, String sender) {
        try {
            if (socket == null || socket.isClosed()) {
                return;
            }
            String msg = "PING," + x + "," + y + "," + (sender == null ? "" : sender);
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length,
                    InetAddress.getByName(broadcastAddr), port);
            socket.send(pkt);
        } catch (Exception e) {
            System.err.println("[Ping] 广播失败: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[256];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(pkt);
                String msg = new String(buf, 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                PingMessage pm = parse(msg);
                if (pm != null && listener != null) {
                    listener.accept(pm);
                }
            } catch (SocketTimeoutException e) {
                // 正常超时，继续循环
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Ping] 接收异常: " + e.getMessage());
                }
            }
        }
    }

    /** 解析文本消息；非 PING 前缀或格式错误返回 null。 */
    public static PingMessage parse(String msg) {
        if (msg == null || !msg.startsWith("PING,")) {
            return null;
        }
        String[] parts = msg.split(",", -1);
        if (parts.length < 3) {
            return null;
        }
        try {
            float x = Float.parseFloat(parts[1]);
            float y = Float.parseFloat(parts[2]);
            String sender = (parts.length > 3) ? parts[3] : "";
            return new PingMessage(x, y, sender);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
    }
}
