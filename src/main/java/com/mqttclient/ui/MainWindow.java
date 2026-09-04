package com.mqttclient.ui;

import com.mqttclient.config.Constants;
import com.mqttclient.config.Config;
import com.mqttclient.mqtt.MqttReceiver;
import com.mqttclient.net.PingChannel;
import com.mqttclient.net.PingChannel.PingMessage;
import com.mqttclient.protobuf.RobotPositionParser;
import com.mqttclient.protobuf.RobotStatusParser;
import com.mqttclient.protobuf.gen.RobotDynamicStatusProto.RobotDynamicStatus;
import com.mqttclient.protobuf.gen.RobotPositionProto.RobotPosition;
import com.mqttclient.protobuf.gen.RobotStaticStatusProto.RobotStaticStatus;
import com.mqttclient.video.UdpVideoProcessor;
import com.mqttclient.video.VideoProcessor;
import com.mqttclient.video.VideoStreamProcessor;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.prefs.Preferences;

/**
 * Swing main window -- game-style HUD version.
 *
 * <p>The video fills the entire window as the background; all other UI is drawn on top as
 * semi-transparent overlays:
 * <ul>
 *   <li><b>ESC</b> -- show / hide the control menu ({@link ControlMenuOverlay})</li>
 *   <li><b>F3</b> -- temporarily toggle the debug HUD ({@link DebugOverlay})</li>
 *   <li><b>F4</b> -- pin / unpin the debug HUD and persist the state to {@link Preferences}</li>
 * </ul>
 *
 * <p>MQTT connection, decoding, and statistics logic is the same as in the non-HUD version; only the
 * presentation changes, by writing into overlay data models and repainting.
 */
public class MainWindow extends JFrame {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String PREF_DEBUG_PINNED = "debugPinned";

    // Overlays
    private final VideoPanel videoPanel = new VideoPanel();
    private final DebugOverlay debugOverlay = new DebugOverlay();
    private final MinimapOverlay minimapOverlay = new MinimapOverlay();
    private final ControlMenuOverlay controlMenu = new ControlMenuOverlay();
    private final StatBarOverlay statBar = new StatBarOverlay();
    private final PingAlertOverlay pingAlertOverlay = new PingAlertOverlay();
    private PingChannel pingChannel;   // Direct client-to-client ping broadcast (UDP, no broker)
    private final JTextArea logArea;

    // Layered container
    private HudRoot layeredPane;

    // HUD visibility state
    private boolean menuVisible = false;
    private boolean debugTemp = false;   // F3 temporary
    private boolean debugPinned;         // F4 pinned (persisted)

    // State
    private MqttReceiver mqtt;
    private VideoStreamProcessor processor;
    private UdpVideoProcessor udpProcessor;
    private volatile boolean connected = false;
    private volatile boolean udpActive = false;   // true = UDP, false = MQTT
    private String clientId = "";
    private Timer renderTimer;

    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);

    public MainWindow() {
        setTitle("MQTT H.264 视频接收器 (Java)");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 900);
        setLocationRelativeTo(null);

        logArea = debugOverlay.getLogArea();
        debugPinned = prefs.getBoolean(PREF_DEBUG_PINNED, false);

        initUi();
        installKeyBindings();
        wireControlMenu();
        startConfigWatcher();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onClose();
            }
        });

        // Initial visibility
        debugOverlay.setPinned(debugPinned);
        refreshOverlayVisibility();

        // Start the direct ping channel (UDP broadcast, no MQTT broker needed)
        startPingChannel();

        appendLog("程序启动，等待 MQTT 连接...");
        appendLog("按键: 0=切换源 F5=重载配置 ESC=控制菜单 F3=调试 F4=常驻 +/-=缩放 1-6=控制");
        appendLog("配置从 config.json 读取，修改后自动热重载 (F5 手动重载)");
        appendLog("小地图: 左键单击地图=标点，点击标题栏=全屏/还原，拖手柄或滚轮=缩放");
        appendLog("左下角状态面板: 显示机器人类型·等级·血量·热量·经验");
        // Show the client ID input dialog after startup
        SwingUtilities.invokeLater(this::askClientId);
    }

    private void initUi() {
        layeredPane = new HudRoot();

        videoPanel.setBackground(Color.BLACK);
        debugOverlay.setVisible(false);
        controlMenu.setVisible(false);

        layeredPane.add(videoPanel, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(debugOverlay, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(minimapOverlay, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(statBar, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(pingAlertOverlay, JLayeredPane.PALETTE_LAYER);
        layeredPane.add(controlMenu, JLayeredPane.MODAL_LAYER);
        // The minimap must receive mouse input (drag to zoom / double-click fullscreen / click to ping),
        // so keep it above the debug HUD to get priority hits.
        layeredPane.moveToFront(minimapOverlay);
        // The ping banner does not intercept the mouse; drawn on top but click-through.
        layeredPane.moveToFront(pingAlertOverlay);

        loadFieldMap();

        setContentPane(layeredPane);
    }

    private void loadFieldMap() {
        try {
            java.awt.image.BufferedImage bg = javax.imageio.ImageIO.read(
                    getClass().getResource("/maps/field.png"));

            MinimapOverlay.MapModel map = new MinimapOverlay.MapModel(
                    " ",
                    28.0,
                    15.0,
                    0.0, 0.0,
                    false,
                    0.0,
                    bg);
            minimapOverlay.setMap(map);
            appendLog("已加载自定义地图");
        } catch (Exception e) {
            appendLog("地图加载失败，使用默认矩形: " + e.getMessage());
        }
    }

    /** Whole-window key bindings so they stay active even when a child component has focus. */
    private void installKeyBindings() {
        JComponent root = getRootPane();
        var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = root.getActionMap();

        im.put(KeyStroke.getKeyStroke("ESCAPE"), "toggleMenu");
        im.put(KeyStroke.getKeyStroke("F3"), "toggleDebug");
        im.put(KeyStroke.getKeyStroke("F4"), "togglePinned");
        im.put(KeyStroke.getKeyStroke("F5"), "reloadConfig");

        // F3 held down shows the debug panel; releasing it hides the panel
        im.put(KeyStroke.getKeyStroke("F3"), "showDebug");
        im.put(KeyStroke.getKeyStroke("released F3"), "hideDebug");

        // Control panel shortcuts: digits 1-6 trigger actions directly, no need to press ESC first
        im.put(KeyStroke.getKeyStroke("0"), "switchSource");
        im.put(KeyStroke.getKeyStroke("1"), "cmdConnect");
        im.put(KeyStroke.getKeyStroke("2"), "cmdDisconnect");
        im.put(KeyStroke.getKeyStroke("3"), "cmdStart");
        im.put(KeyStroke.getKeyStroke("4"), "cmdStop");
        im.put(KeyStroke.getKeyStroke("5"), "cmdClearLog");
        im.put(KeyStroke.getKeyStroke("6"), "cmdChangeId");

        // Minimap zoom: + / = zoom in, - / _ zoom out (including numpad)
        im.put(KeyStroke.getKeyStroke("typed +"), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke("typed ="), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke("typed -"), "minimapZoomOut");
        im.put(KeyStroke.getKeyStroke("typed _"), "minimapZoomOut");
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD, 0), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT, 0), "minimapZoomOut");

        am.put("switchSource", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchVideoSource();
            }
        });
        am.put("toggleMenu", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleControlMenu();
            }
        });
        am.put("showDebug", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDebug();
            }
        });
        am.put("hideDebug", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideDebug();
            }
        });
        am.put("togglePinned", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleDebugPinned();
            }
        });
        am.put("reloadConfig", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onReloadConfig();
            }
        });
        am.put("cmdConnect", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnConnect.doClick();
            }
        });
        am.put("cmdDisconnect", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnDisconnect.doClick();
            }
        });
        am.put("cmdStart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnStart.doClick();
            }
        });
        am.put("cmdStop", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnStop.doClick();
            }
        });
        am.put("cmdClearLog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnClearLog.doClick();
            }
        });
        am.put("cmdChangeId", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controlMenu.btnChangeId.doClick();
            }
        });
        am.put("minimapZoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                minimapOverlay.zoomIn();
            }
        });
        am.put("minimapZoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                minimapOverlay.zoomOut();
            }
        });
    }

    private void wireControlMenu() {
        controlMenu.btnConnect.addActionListener(e -> onConnect());
        controlMenu.btnDisconnect.addActionListener(e -> onDisconnect());
        controlMenu.btnStart.addActionListener(e -> onStart());
        controlMenu.btnStop.addActionListener(e -> onStop());
        controlMenu.btnClearLog.addActionListener(e -> {
            logArea.setText("");
            appendLog("日志已清空");
        });
        controlMenu.btnChangeId.addActionListener(e -> onChangeClientId());
        controlMenu.btnSwitchSource.addActionListener(e -> switchVideoSource());
        controlMenu.btnReloadConfig.addActionListener(e -> onReloadConfig());
        controlMenu.setOnScrimClick(this::toggleControlMenu);

        controlMenu.btnDisconnect.setEnabled(false);
        controlMenu.btnStart.setEnabled(false);
        controlMenu.btnStop.setEnabled(false);
    }

    // ==== HUD visibility ====

    private void toggleControlMenu() {
        menuVisible = !menuVisible;
        controlMenu.setVisible(menuVisible);
        if (menuVisible) {
            controlMenu.repaint();
        }
    }

    /** F3 pressed -> show the debug panel. */
    private void showDebug() {
        debugTemp = true;
        refreshOverlayVisibility();
    }

    /** F3 released -> hide the debug panel (unless pinned with F4). */
    private void hideDebug() {
        debugTemp = false;
        refreshOverlayVisibility();
    }

    private void toggleDebugPinned() {
        debugPinned = !debugPinned;
        prefs.putBoolean(PREF_DEBUG_PINNED, debugPinned);
        debugOverlay.setPinned(debugPinned);
        refreshOverlayVisibility();
        appendLog("调试数据常驻显示: " + (debugPinned ? "开启" : "关闭"));
    }

    private void refreshOverlayVisibility() {
        debugOverlay.setVisible(debugTemp || debugPinned);
    }

    // ==== Button logic ====

    private void onConnect() {
        if (clientId == null || clientId.isEmpty()) {
            appendLog("客户端 ID 未设置，请重新输入");
            askClientId();
            return;
        }
        appendLog("正在连接 MQTT 服务器...");
        controlMenu.btnConnect.setEnabled(false);

        mqtt = new MqttReceiver(Constants.DEFAULT_BROKER_HOST, Constants.DEFAULT_BROKER_PORT, clientId);
        mqtt.setConnectionStatusListener((ok, msg) ->
                SwingUtilities.invokeLater(() -> onMqttStatusChanged(ok, msg)));
        mqtt.setRobotPositionListener(this::onRobotPosition);
        mqtt.setRobotStaticStatusListener(this::onRobotStaticStatus);
        mqtt.setRobotDynamicStatusListener(this::onRobotDynamicStatus);

        if (mqtt.connect()) {
            appendLog("MQTT 连接请求已发送，等待确认...");
        } else {
            appendLog("MQTT 连接请求失败，请检查网络");
            controlMenu.btnConnect.setEnabled(true);
        }
    }

    private void onMqttStatusChanged(boolean ok, String message) {
        connected = ok;
        debugOverlay.setConnected(ok);
        if (ok) {
            setStatus("已连接 - 等待视频数据");
            controlMenu.btnDisconnect.setEnabled(true);
            controlMenu.btnStart.setEnabled(true);
            appendLog("MQTT 连接成功");
        } else {
            setStatus("连接失败/已断开");
            controlMenu.btnConnect.setEnabled(true);
            controlMenu.btnDisconnect.setEnabled(false);
            controlMenu.btnStart.setEnabled(false);
            appendLog("MQTT " + message);
        }
    }

    /** Starts the direct ping channel (UDP broadcast, no broker / no protobuf) and wires up minimap clicks. */
    private void startPingChannel() {
        pingChannel = new PingChannel(Constants.PING_PORT, Constants.PING_BROADCAST_ADDR,
                this::onPingReceived);
        pingChannel.start();

        // Clicking the minimap -> broadcast a ping over UDP (received by all clients on the same
        // Ethernet) plus show it locally immediately
        minimapOverlay.setOnPingListener((x, y) -> {
            if (pingChannel != null) {
                pingChannel.broadcastPing(x.floatValue(), y.floatValue(), clientId);
            }
            minimapOverlay.showPing(x, y, clientId);
            appendLog("标点: (" + String.format("%.1f", x) + ", "
                    + String.format("%.1f", y) + ")");
        });
    }

    /** Handles an incoming ping (PingChannel receiver thread): switches to the EDT to show the ripple + alert banner. */
    private void onPingReceived(PingMessage ping) {
        float x = ping.x();
        float y = ping.y();
        String sender = ping.sender();
        SwingUtilities.invokeLater(() -> {
            minimapOverlay.showPing(x, y, sender);
            pingAlertOverlay.showAlert(sender, x, y);
        });
    }

    /** Handles an incoming robot position (Paho thread): parses protobuf and refreshes the minimap on the EDT. */
    private void onRobotPosition(byte[] payload) {
        try {
            RobotPosition pos = RobotPositionParser.parsePayload(payload);
            double x = pos.getX();
            double y = pos.getY();
            double yaw = pos.getYaw();
            int robotId = pos.hasRobotId() ? pos.getRobotId() : 0;
            SwingUtilities.invokeLater(() -> minimapOverlay.setRobotPosition(x, y, yaw, robotId));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> appendLog("机器人位置解析失败: " + e.getMessage()));
        }
    }

    /** Handles an incoming static robot status (Paho thread): parses protobuf and refreshes the status panel on the EDT. */
    private void onRobotStaticStatus(byte[] payload) {
        try {
            RobotStaticStatus s = RobotStatusParser.parseStaticStatus(payload);
            int type = s.hasRobotType() ? s.getRobotType() : 0;
            int lv = s.hasLevel() ? s.getLevel() : 0;
            int hp = s.hasMaxHealth() ? s.getMaxHealth() : 100;
            int heat = s.hasMaxHeat() ? s.getMaxHeat() : 100;
            SwingUtilities.invokeLater(() ->
                    statBar.setStaticStatus(type, lv, hp, heat));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> appendLog("静态状态解析失败: " + e.getMessage()));
        }
    }

    /** Handles an incoming dynamic robot status (Paho thread): parses protobuf and refreshes the status panel on the EDT. */
    private void onRobotDynamicStatus(byte[] payload) {
        try {
            RobotDynamicStatus d = RobotStatusParser.parseDynamicStatus(payload);
            int hp = d.hasCurrentHealth() ? d.getCurrentHealth() : 0;
            float heat = d.hasCurrentHeat() ? d.getCurrentHeat() : 0f;
            int xp = d.hasCurrentExperience() ? d.getCurrentExperience() : 0;
            int xpMax = d.hasExperienceForUpgrade() ? d.getExperienceForUpgrade() : 100;
            SwingUtilities.invokeLater(() ->
                    statBar.setDynamicStatus(hp, heat, xp, xpMax));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> appendLog("动态状态解析失败: " + e.getMessage()));
        }
    }

    private void onDisconnect() {
        onStop();
        if (mqtt != null) {
            mqtt.disconnect();
            mqtt = null;
        }
        connected = false;
        debugOverlay.setConnected(false);
        setStatus("已断开");
        controlMenu.btnConnect.setEnabled(true);
        controlMenu.btnDisconnect.setEnabled(false);
        controlMenu.btnStart.setEnabled(false);
        appendLog("MQTT 连接已断开");
    }

    private void onStart() {
        if (udpActive) {
            startUdpProcessor();
        } else {
            startMqttProcessor();
        }
    }

    private void startMqttProcessor() {
        if (!connected || mqtt == null) {
            appendLog("请先连接 MQTT");
            return;
        }
        processor = new VideoProcessor(mqtt);
        processor.setStatusListener(msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));
        processor.setStatsListener((packets, frames, decodeFps, displayFps, lost) ->
                SwingUtilities.invokeLater(() -> debugOverlay.setStats(String.format(
                        "[MQTT] 包: %d | 帧: %d | 解码FPS: %.1f | 显示FPS: %.1f | 丢包: %d",
                        packets, frames, decodeFps, displayFps, lost))));
        processor.start();

        renderTimer = new Timer(Constants.RENDER_INTERVAL_MS, e -> {
            BufferedImage frame = processor.takeLatestFrameForRender();
            if (frame != null) {
                videoPanel.setImage(frame);
            }
        });
        renderTimer.start();

        controlMenu.btnStart.setEnabled(false);
        controlMenu.btnStop.setEnabled(true);
        setStatus("MQTT 解码中...");
        appendLog("MQTT 视频处理线程已启动");
    }

    private void startUdpProcessor() {
        if (udpProcessor != null) {
            udpProcessor.stopProcessor();
        }
        udpProcessor = new UdpVideoProcessor(Constants.UDP_HOST);
        udpProcessor.setStatusListener(msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));
        udpProcessor.setStatsListener((packets, frames, decodeFps, displayFps, lost) ->
                SwingUtilities.invokeLater(() -> debugOverlay.setStats(String.format(
                        "[UDP] 包: %d | 帧: %d | 解码FPS: %.1f | 显示FPS: %.1f | 丢片: %d",
                        packets, frames, decodeFps, displayFps, lost))));
        udpProcessor.start();
        processor = udpProcessor;

        renderTimer = new Timer(Constants.RENDER_INTERVAL_MS, e -> {
            BufferedImage frame = processor.takeLatestFrameForRender();
            if (frame != null) {
                videoPanel.setImage(frame);
            }
        });
        renderTimer.start();

        controlMenu.btnStart.setEnabled(false);
        controlMenu.btnStop.setEnabled(true);
        setStatus("UDP HEVC 解码中...");
        appendLog("UDP 视频处理线程已启动 (" + Constants.UDP_HOST + ":" + Constants.UDP_PORT + ")");
    }

    /** Switches the video source between MQTT and UDP. */
    private void switchVideoSource() {
        // Stop the current processor
        if (renderTimer != null) {
            renderTimer.stop();
            renderTimer = null;
        }
        if (processor != null) {
            processor.stopProcessor();
            processor = null;
        }

        udpActive = !udpActive;

        String label = udpActive ? "UDP" : "MQTT";
        appendLog("视频源已切换至: " + label);
        debugOverlay.setSourceLabel(label);

        if (udpActive) {
            startUdpProcessor();
        } else if (connected && mqtt != null) {
            startMqttProcessor();
        } else {
            appendLog("MQTT 未连接，请先连接后再启动");
            controlMenu.btnStart.setEnabled(true);
            controlMenu.btnStop.setEnabled(false);
        }
    }

    /** Reloads config.json (F5 or the "Reload Config" menu action). */
    private void onReloadConfig() {
        Constants.reload();
        appendLog("配置已重新加载");
        debugOverlay.repaint();
        appendLog("提示: MQTT 连接参数需重连生效；UDP/缓冲/分辨率参数需停止后再启动生效");
    }

    /** Watches config.json in the background and hot-reloads on changes (polls the mtime every 2 seconds). */
    private void startConfigWatcher() {
        Thread watcher = new Thread(() -> {
            long last = Config.lastModified();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = Config.lastModified();
                if (now != last && now != 0) {
                    last = now;
                    SwingUtilities.invokeLater(this::onReloadConfig);
                }
            }
        }, "config-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void onStop() {
        if (renderTimer != null) {
            renderTimer.stop();
            renderTimer = null;
        }
        if (processor != null) {
            processor.stopProcessor();
            processor = null;
        }
        controlMenu.btnStart.setEnabled(connected || udpActive);
        controlMenu.btnStop.setEnabled(false);
        setStatus("解码已停止");
        appendLog("视频处理线程已停止");
    }

    private void onChangeClientId() {
        if (connected) {
            appendLog("无法修改客户端 ID：当前已连接，请先断开");
            return;
        }
        String input = JOptionPane.showInputDialog(this,
                "请输入新的客户端 ID（留空则自动生成）:", clientId);
        if (input != null) {
            clientId = input.trim().isEmpty() ? generateClientId() : input.trim();
            debugOverlay.setClientId(clientId);
            appendLog("客户端 ID 已更新为: " + clientId);
        }
    }

    private void askClientId() {
        String input = JOptionPane.showInputDialog(this,
                "请输入客户端 ID（留空则自动生成）:", "");
        clientId = (input == null || input.trim().isEmpty()) ? generateClientId() : input.trim();
        debugOverlay.setClientId(clientId);
        appendLog("客户端 ID: " + clientId);
    }

    private String generateClientId() {
        return "h264_recv_" + (System.currentTimeMillis() / 1000) + "_" + (100 + new Random().nextInt(900));
    }

    // ==== Display and logging ====

    private void setStatus(String text) {
        debugOverlay.setStatus(text);
    }

    private void appendLog(String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        logArea.append("[" + ts + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void onClose() {
        appendLog("正在关闭程序...");
        onStop();
        if (pingChannel != null) {
            pingChannel.close();
        }
        if (udpProcessor != null) {
            udpProcessor.stopProcessor();
            udpProcessor = null;
        }
        if (mqtt != null) {
            mqtt.disconnect();
        }
        dispose();
        System.exit(0);
    }

    /** Layered root container: keeps every overlay stretched to fill the window. */
    private static class HudRoot extends JLayeredPane {
        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            for (java.awt.Component c : getComponents()) {
                c.setBounds(0, 0, w, h);
            }
        }
    }

    /** Video display panel: draws the latest frame centered and aspect-ratio-preserving. */
    private static class VideoPanel extends javax.swing.JPanel {
        private BufferedImage image;

        VideoPanel() {
            setOpaque(true);
            setBackground(Color.BLACK);
        }

        void setImage(BufferedImage img) {
            this.image = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                g.setColor(new Color(0x9e9e9e));
                String text = "等待视频流...";
                int tw = g.getFontMetrics().stringWidth(text);
                g.drawString(text, (getWidth() - tw) / 2, getHeight() / 2);
                return;
            }
            // Scale while preserving the aspect ratio
            int pw = getWidth() - 20;
            int ph = getHeight() - 20;
            double scale = Math.min((double) pw / image.getWidth(), (double) ph / image.getHeight());
            int dw = (int) (image.getWidth() * scale);
            int dh = (int) (image.getHeight() * scale);
            int x = (getWidth() - dw) / 2;
            int y = (getHeight() - dh) / 2;
            g.drawImage(image, x, y, dw, dh, this);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(600, 500);
        }
    }
}
