package com.mqttclient.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Direct client-to-client marker broadcast channel (UDP broadcast; no broker, no protobuf).
 *
 * <p>All clients on the same Ethernet listen on a fixed UDP port; when one marks a point it sends
 * a simple text message {@code PING,x,y,sender} to the LAN broadcast address, and every other
 * client (including the sender itself) receives it.
 *
 * <p>Message format (plain text, comma-separated):
 * <pre>
 *   PING,12.5,7.25,clientId
 * </pre>
 */
public class PingChannel implements AutoCloseable {

    /** A received marker message. */
    public record PingMessage(float x, float y, String sender) {
    }

    private final int port;
    private final String broadcastAddr;
    private final Consumer<PingMessage> listener;

    private volatile boolean running = true;
    private DatagramSocket socket;
    private Thread receiveThread;

    /**
     * @param port          Listen/broadcast port (identical across all clients on the same LAN)
     * @param broadcastAddr LAN broadcast address, e.g. {@code 255.255.255.255}
     * @param listener      Callback invoked when a marker is received (runs on the receive thread)
     */
    public PingChannel(int port, String broadcastAddr, Consumer<PingMessage> listener) {
        this.port = port;
        this.broadcastAddr = broadcastAddr;
        this.listener = listener;
    }

    /** Starts listening for broadcasts (dedicated receive thread). */
    public void start() {
        try {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
            socket.setSoTimeout(200);   // Wake periodically to check running
            receiveThread = new Thread(this::receiveLoop, "PingChannel");
            receiveThread.setDaemon(true);
            receiveThread.start();
            System.out.println("[Ping] 监听 UDP " + port + " 广播标点");
        } catch (SocketException e) {
            System.err.println("[Ping] 监听失败: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a marker.
     *
     * @param x      World coordinate X (meters)
     * @param y      World coordinate Y (meters)
     * @param sender Sender client ID
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
                // Normal timeout; continue the loop
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Ping] 接收异常: " + e.getMessage());
                }
            }
        }
    }

    /** Parses a text message; returns null if it lacks the PING prefix or is malformed. */
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
