
// package com.mqttclient.ui;

// import com.mqttclient.config.Constants;
// import com.mqttclient.mqtt.MqttReceiver;
// import com.mqttclient.protobuf.RobotPositionParser;
// import com.mqttclient.protobuf.gen.RobotPositionProto.RobotPosition;
// import com.mqttclient.video.VideoProcessor;

// import javax.swing.AbstractAction;
// import javax.swing.JComponent;
// import javax.swing.JFrame;
// import javax.swing.JLayeredPane;
// import javax.swing.JOptionPane;
// import javax.swing.JTextArea;
// import javax.swing.KeyStroke;
// import javax.swing.SwingUtilities;
// import javax.swing.Timer;
// import java.awt.Color;
// import java.awt.Dimension;
// import java.awt.Graphics;
// import java.awt.event.ActionEvent;
// import java.awt.image.BufferedImage;
// import java.time.LocalTime;
// import java.time.format.DateTimeFormatter;
// import java.util.Random;
// import java.util.prefs.Preferences;

// /**
//  * Swing 主窗口 —— 游戏化 HUD 版。
//  *
//  * <p>视频画面铺满整窗作为背景，其余 UI 均为漂浮其上的半透明叠加层：
//  * <ul>
//  *   <li><b>ESC</b> —— 弹出 / 收起控制菜单（{@link ControlMenuOverlay}）</li>
//  *   <li><b>F3</b> —— 临时切换调试数据 HUD（{@link DebugOverlay}）</li>
//  *   <li><b>F4</b> —— 锁定 / 解锁调试数据常驻显示，并持久化到 {@link Preferences}</li>
//  * </ul>
//  *
//  * <p>MQTT 连接、解码、统计等业务逻辑与非 HUD 版一致，仅呈现方式改为写入
//  * 叠加层的数据模型后重绘。
//  */
// public class MainWindow extends JFrame {

//     private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
//     private static final String PREF_DEBUG_PINNED = "debugPinned";

//     // 叠加层
//     private final VideoPanel videoPanel = new VideoPanel();
//     private final DebugOverlay debugOverlay = new DebugOverlay();
//     private final MinimapOverlay minimapOverlay = new MinimapOverlay();
//     private final ControlMenuOverlay controlMenu = new ControlMenuOverlay();
//     private final JTextArea logArea;

//     // 分层容器
//     private HudRoot layeredPane;

//     // HUD 显隐状态
//     private boolean menuVisible = false;
//     private boolean debugTemp = false;   // F3 临时
//     private boolean debugPinned;         // F4 常驻（持久化）

//     // 状态
//     private MqttReceiver mqtt;
//     private VideoProcessor processor;
//     private volatile boolean connected = false;
//     private String clientId = "";
//     private Timer renderTimer;

//     private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);

//     public MainWindow() {
//         setTitle("MQTT H.264 视频接收器 (Java)");
//         setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
//         setSize(1200, 900);
//         setLocationRelativeTo(null);

//         logArea = debugOverlay.getLogArea();
//         debugPinned = prefs.getBoolean(PREF_DEBUG_PINNED, false);

//         initUi();
//         installKeyBindings();
//         wireControlMenu();

//         addWindowListener(new java.awt.event.WindowAdapter() {
//             @Override
//             public void windowClosing(java.awt.event.WindowEvent e) {
//                 onClose();
//             }
//         });

//         // 初始显隐
//         debugOverlay.setPinned(debugPinned);
//         refreshOverlayVisibility();

//         appendLog("程序启动，等待 MQTT 连接...");
//         appendLog("按键: ESC=控制菜单  F3=调试数据  F4=常驻显示");
//         // 启动后弹出客户端 ID 输入框
//         SwingUtilities.invokeLater(this::askClientId);
//     }

//     private void initUi() {
//         layeredPane = new HudRoot();

//         videoPanel.setBackground(Color.BLACK);
//         debugOverlay.setVisible(false);
//         controlMenu.setVisible(false);

//         layeredPane.add(videoPanel, JLayeredPane.DEFAULT_LAYER);
//         layeredPane.add(minimapOverlay, JLayeredPane.PALETTE_LAYER);
//         layeredPane.add(debugOverlay, JLayeredPane.PALETTE_LAYER);
//         layeredPane.add(controlMenu, JLayeredPane.MODAL_LAYER);

//         loadFieldMap();

//         setContentPane(layeredPane);
//     }
//     /** 加载自定义背景地图。 */
//     private void loadFieldMap() {
//         try {
//             java.awt.image.BufferedImage bg = javax.imageio.ImageIO.read(
//                     getClass().getResource("/maps/field.png"));

//             MinimapOverlay.MapModel map = new MinimapOverlay.MapModel(
//                     "我的场地",  
//                     28.0,      
//                     15.0,     
//                     0.0, 0.0,   
//                     false,    
//                     0.0,        
//                     bg);       
//             minimapOverlay.setMap(map);
//             appendLog("已加载自定义地图");
//         } catch (Exception e) {
//             appendLog("地图加载失败，使用默认矩形: " + e.getMessage());
//         }
//     }
//     /** 全窗口范围的按键绑定，避免焦点在子组件上时失效。 */
//     private void installKeyBindings() {
//         JComponent root = getRootPane();
//         var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
//         var am = root.getActionMap();

//         im.put(KeyStroke.getKeyStroke("ESCAPE"), "toggleMenu");
//         im.put(KeyStroke.getKeyStroke("F3"), "toggleDebug");
//         im.put(KeyStroke.getKeyStroke("F4"), "togglePinned");

//         am.put("toggleMenu", new AbstractAction() {
//             @Override
//             public void actionPerformed(ActionEvent e) {
//                 toggleControlMenu();
//             }
//         });
//         am.put("toggleDebug", new AbstractAction() {
//             @Override
//             public void actionPerformed(ActionEvent e) {
//                 toggleDebugTemp();
//             }
//         });
//         am.put("togglePinned", new AbstractAction() {
//             @Override
//             public void actionPerformed(ActionEvent e) {
//                 toggleDebugPinned();
//             }
//         });
//     }

//     private void wireControlMenu() {
//         controlMenu.btnConnect.addActionListener(e -> onConnect());
//         controlMenu.btnDisconnect.addActionListener(e -> onDisconnect());
//         controlMenu.btnStart.addActionListener(e -> onStart());
//         controlMenu.btnStop.addActionListener(e -> onStop());
//         controlMenu.btnClearLog.addActionListener(e -> {
//             logArea.setText("");
//             appendLog("日志已清空");
//         });
//         controlMenu.btnChangeId.addActionListener(e -> onChangeClientId());
//         controlMenu.setOnScrimClick(this::toggleControlMenu);

//         controlMenu.btnDisconnect.setEnabled(false);
//         controlMenu.btnStart.setEnabled(false);
//         controlMenu.btnStop.setEnabled(false);
//     }

//     // ==== HUD 显隐 ====

//     private void toggleControlMenu() {
//         menuVisible = !menuVisible;
//         controlMenu.setVisible(menuVisible);
//         if (menuVisible) {
//             controlMenu.repaint();
//         }
//     }

//     private void toggleDebugTemp() {
//         debugTemp = !debugTemp;
//         refreshOverlayVisibility();
//     }

//     private void toggleDebugPinned() {
//         debugPinned = !debugPinned;
//         prefs.putBoolean(PREF_DEBUG_PINNED, debugPinned);
//         debugOverlay.setPinned(debugPinned);
//         refreshOverlayVisibility();
//         appendLog("调试数据常驻显示: " + (debugPinned ? "开启" : "关闭"));
//     }

//     private void refreshOverlayVisibility() {
//         debugOverlay.setVisible(debugTemp || debugPinned);
//     }

//     // ==== 按钮逻辑 ====

//     private void onConnect() {
//         if (clientId == null || clientId.isEmpty()) {
//             appendLog("客户端 ID 未设置，请重新输入");
//             askClientId();
//             return;
//         }
//         appendLog("正在连接 MQTT 服务器...");
//         controlMenu.btnConnect.setEnabled(false);

//         mqtt = new MqttReceiver(Constants.DEFAULT_BROKER_HOST, Constants.DEFAULT_BROKER_PORT, clientId);
//         mqtt.setConnectionStatusListener((ok, msg) ->
//                 SwingUtilities.invokeLater(() -> onMqttStatusChanged(ok, msg)));
//         mqtt.setRobotPositionListener(this::onRobotPosition);

//         if (mqtt.connect()) {
//             appendLog("MQTT 连接请求已发送，等待确认...");
//         } else {
//             appendLog("MQTT 连接请求失败，请检查网络");
//             controlMenu.btnConnect.setEnabled(true);
//         }
//     }

//     private void onMqttStatusChanged(boolean ok, String message) {
//         connected = ok;
//         debugOverlay.setConnected(ok);
//         if (ok) {
//             setStatus("已连接 - 等待视频数据");
//             controlMenu.btnDisconnect.setEnabled(true);
//             controlMenu.btnStart.setEnabled(true);
//             appendLog("MQTT 连接成功");
//         } else {
//             setStatus("连接失败/已断开");
//             controlMenu.btnConnect.setEnabled(true);
//             controlMenu.btnDisconnect.setEnabled(false);
//             controlMenu.btnStart.setEnabled(false);
//             appendLog("MQTT " + message);
//         }
//     }

//     /** 收到机器人位置（Paho 线程）：解析 protobuf 并在 EDT 上刷新小地图。 */
//     private void onRobotPosition(byte[] payload) {
//         try {
//             RobotPosition pos = RobotPositionParser.parsePayload(payload);
//             double x = pos.getX();
//             double y = pos.getY();
//             double yaw = pos.getYaw();
//             SwingUtilities.invokeLater(() -> minimapOverlay.setRobotPosition(x, y, yaw));
//         } catch (Exception e) {
//             SwingUtilities.invokeLater(() -> appendLog("机器人位置解析失败: " + e.getMessage()));
//         }
//     }

//     private void onDisconnect() {
//         onStop();
//         if (mqtt != null) {
//             mqtt.disconnect();
//             mqtt = null;
//         }
//         connected = false;
//         debugOverlay.setConnected(false);
//         setStatus("已断开");
//         controlMenu.btnConnect.setEnabled(true);
//         controlMenu.btnDisconnect.setEnabled(false);
//         controlMenu.btnStart.setEnabled(false);
//         appendLog("MQTT 连接已断开");
//     }

//     private void onStart() {
//         if (!connected || mqtt == null) {
//             appendLog("请先连接 MQTT");
//             return;
//         }
//         processor = new VideoProcessor(mqtt);
//         processor.setStatusListener(msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));
//         processor.setStatsListener((packets, frames, decodeFps, displayFps, lost) ->
//                 SwingUtilities.invokeLater(() -> debugOverlay.setStats(String.format(
//                         "包: %d | 帧: %d | 解码FPS: %.1f | 显示FPS: %.1f | 丢包: %d",
//                         packets, frames, decodeFps, displayFps, lost))));
//         processor.start();

//         // 渲染定时器：定时取最新帧刷新显示（对应 QTimer）
//         renderTimer = new Timer(Constants.RENDER_INTERVAL_MS, e -> {
//             BufferedImage frame = processor.takeLatestFrameForRender();
//             if (frame != null) {
//                 videoPanel.setImage(frame);
//             }
//         });
//         renderTimer.start();

//         controlMenu.btnStart.setEnabled(false);
//         controlMenu.btnStop.setEnabled(true);
//         setStatus("解码中...");
//         appendLog("视频处理线程已启动");
//     }

//     private void onStop() {
//         if (renderTimer != null) {
//             renderTimer.stop();
//             renderTimer = null;
//         }
//         if (processor != null) {
//             processor.stopProcessor();
//             processor = null;
//         }
//         controlMenu.btnStart.setEnabled(connected);
//         controlMenu.btnStop.setEnabled(false);
//         setStatus("解码已停止");
//         appendLog("视频处理线程已停止");
//     }

//     private void onChangeClientId() {
//         if (connected) {
//             appendLog("无法修改客户端 ID：当前已连接，请先断开");
//             return;
//         }
//         String input = JOptionPane.showInputDialog(this,
//                 "请输入新的客户端 ID（留空则自动生成）:", clientId);
//         if (input != null) {
//             clientId = input.trim().isEmpty() ? generateClientId() : input.trim();
//             debugOverlay.setClientId(clientId);
//             appendLog("客户端 ID 已更新为: " + clientId);
//         }
//     }

//     private void askClientId() {
//         String input = JOptionPane.showInputDialog(this,
//                 "请输入客户端 ID（留空则自动生成）:", "");
//         clientId = (input == null || input.trim().isEmpty()) ? generateClientId() : input.trim();
//         debugOverlay.setClientId(clientId);
//         appendLog("客户端 ID: " + clientId);
//     }

//     private String generateClientId() {
//         return "h264_recv_" + (System.currentTimeMillis() / 1000) + "_" + (100 + new Random().nextInt(900));
//     }

//     // ==== 显示与日志 ====

//     private void setStatus(String text) {
//         debugOverlay.setStatus(text);
//     }

//     private void appendLog(String message) {
//         String ts = LocalTime.now().format(TIME_FMT);
//         logArea.append("[" + ts + "] " + message + "\n");
//         logArea.setCaretPosition(logArea.getDocument().getLength());
//     }

//     private void onClose() {
//         appendLog("正在关闭程序...");
//         onStop();
//         if (mqtt != null) {
//             mqtt.disconnect();
//         }
//         dispose();
//         System.exit(0);
//     }

//     /** 分层根容器：让所有叠加层始终铺满窗口。 */
//     private static class HudRoot extends JLayeredPane {
//         @Override
//         public void doLayout() {
//             int w = getWidth();
//             int h = getHeight();
//             for (java.awt.Component c : getComponents()) {
//                 c.setBounds(0, 0, w, h);
//             }
//         }
//     }

//     /** 视频显示面板：按比例缩放居中绘制最新帧。 */
//     private static class VideoPanel extends javax.swing.JPanel {
//         private BufferedImage image;

//         VideoPanel() {
//             setOpaque(true);
//             setBackground(Color.BLACK);
//         }

//         void setImage(BufferedImage img) {
//             this.image = img;
//             repaint();
//         }

//         @Override
//         protected void paintComponent(Graphics g) {
//             super.paintComponent(g);
//             if (image == null) {
//                 g.setColor(new Color(0x9e9e9e));
//                 String text = "等待视频流...";
//                 int tw = g.getFontMetrics().stringWidth(text);
//                 g.drawString(text, (getWidth() - tw) / 2, getHeight() / 2);
//                 return;
//             }
//             // 保持宽高比缩放
//             int pw = getWidth() - 20;
//             int ph = getHeight() - 20;
//             double scale = Math.min((double) pw / image.getWidth(), (double) ph / image.getHeight());
//             int dw = (int) (image.getWidth() * scale);
//             int dh = (int) (image.getHeight() * scale);
//             int x = (getWidth() - dw) / 2;
//             int y = (getHeight() - dh) / 2;
//             g.drawImage(image, x, y, dw, dh, this);
//         }

//         @Override
//         public Dimension getPreferredSize() {
//             return new Dimension(600, 500);
//         }
//     }
// }



package com.mqttclient.ui;

import com.mqttclient.config.Constants;
import com.mqttclient.mqtt.MqttReceiver;
import com.mqttclient.protobuf.RobotPositionParser;
import com.mqttclient.protobuf.RobotStatusParser;
import com.mqttclient.protobuf.gen.RobotDynamicStatusProto.RobotDynamicStatus;
import com.mqttclient.protobuf.gen.RobotPositionProto.RobotPosition;
import com.mqttclient.protobuf.gen.RobotStaticStatusProto.RobotStaticStatus;
import com.mqttclient.video.VideoProcessor;

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
 * Swing 主窗口 —— 游戏化 HUD 版。
 *
 * <p>视频画面铺满整窗作为背景，其余 UI 均为漂浮其上的半透明叠加层：
 * <ul>
 *   <li><b>ESC</b> —— 弹出 / 收起控制菜单（{@link ControlMenuOverlay}）</li>
 *   <li><b>F3</b> —— 临时切换调试数据 HUD（{@link DebugOverlay}）</li>
 *   <li><b>F4</b> —— 锁定 / 解锁调试数据常驻显示，并持久化到 {@link Preferences}</li>
 * </ul>
 *
 * <p>MQTT 连接、解码、统计等业务逻辑与非 HUD 版一致，仅呈现方式改为写入
 * 叠加层的数据模型后重绘。
 */
public class MainWindow extends JFrame {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String PREF_DEBUG_PINNED = "debugPinned";

    // 叠加层
    private final VideoPanel videoPanel = new VideoPanel();
    private final DebugOverlay debugOverlay = new DebugOverlay();
    private final MinimapOverlay minimapOverlay = new MinimapOverlay();
    private final ControlMenuOverlay controlMenu = new ControlMenuOverlay();
    private final StatBarOverlay statBar = new StatBarOverlay();
    private final JTextArea logArea;

    // 分层容器
    private HudRoot layeredPane;

    // HUD 显隐状态
    private boolean menuVisible = false;
    private boolean debugTemp = false;   // F3 临时
    private boolean debugPinned;         // F4 常驻（持久化）

    // 状态
    private MqttReceiver mqtt;
    private VideoProcessor processor;
    private volatile boolean connected = false;
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

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onClose();
            }
        });

        // 初始显隐
        debugOverlay.setPinned(debugPinned);
        refreshOverlayVisibility();

        appendLog("程序启动，等待 MQTT 连接...");
        appendLog("按键: ESC=控制菜单  F3=调试  F4=常驻  +/-=小地图缩放  1-6=控制功能");
        appendLog("小地图: 拖动左上角手柄或滚轮缩放，双击全屏/还原");
        appendLog("左下角状态面板: 显示机器人类型·等级·血量·热量·经验");
        // 启动后弹出客户端 ID 输入框
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
        layeredPane.add(controlMenu, JLayeredPane.MODAL_LAYER);
        // 小地图需要接收鼠标（拖动缩放 / 双击全屏），置于调试 HUD 之上以优先命中
        layeredPane.moveToFront(minimapOverlay);

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

    /** 全窗口范围的按键绑定，避免焦点在子组件上时失效。 */
    private void installKeyBindings() {
        JComponent root = getRootPane();
        var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = root.getActionMap();

        im.put(KeyStroke.getKeyStroke("ESCAPE"), "toggleMenu");
        im.put(KeyStroke.getKeyStroke("F3"), "toggleDebug");
        im.put(KeyStroke.getKeyStroke("F4"), "togglePinned");

        // F3 按住显示调试，松手消失
        im.put(KeyStroke.getKeyStroke("F3"), "showDebug");
        im.put(KeyStroke.getKeyStroke("released F3"), "hideDebug");

        // 控制面板快捷键：数字 1~6 直接触发，无需先按 ESC
        im.put(KeyStroke.getKeyStroke("1"), "cmdConnect");
        im.put(KeyStroke.getKeyStroke("2"), "cmdDisconnect");
        im.put(KeyStroke.getKeyStroke("3"), "cmdStart");
        im.put(KeyStroke.getKeyStroke("4"), "cmdStop");
        im.put(KeyStroke.getKeyStroke("5"), "cmdClearLog");
        im.put(KeyStroke.getKeyStroke("6"), "cmdChangeId");

        // 小地图缩放：+ / = 放大，- / _ 缩小（含小键盘）
        im.put(KeyStroke.getKeyStroke("typed +"), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke("typed ="), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke("typed -"), "minimapZoomOut");
        im.put(KeyStroke.getKeyStroke("typed _"), "minimapZoomOut");
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD, 0), "minimapZoomIn");
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT, 0), "minimapZoomOut");

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
        controlMenu.setOnScrimClick(this::toggleControlMenu);

        controlMenu.btnDisconnect.setEnabled(false);
        controlMenu.btnStart.setEnabled(false);
        controlMenu.btnStop.setEnabled(false);
    }

    // ==== HUD 显隐 ====

    private void toggleControlMenu() {
        menuVisible = !menuVisible;
        controlMenu.setVisible(menuVisible);
        if (menuVisible) {
            controlMenu.repaint();
        }
    }

    /** F3 按下 → 显示调试面板。 */
    private void showDebug() {
        debugTemp = true;
        refreshOverlayVisibility();
    }

    /** F3 松开 → 隐藏调试面板（除非已 F4 锁定）。 */
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

    // ==== 按钮逻辑 ====

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

    /** 收到机器人位置（Paho 线程）：解析 protobuf 并在 EDT 上刷新小地图。 */
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

    /** 收到机器人静态状态（Paho 线程）：解析 protobuf 并在 EDT 刷新状态面板。 */
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

    /** 收到机器人动态状态（Paho 线程）：解析 protobuf 并在 EDT 刷新状态面板。 */
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
        if (!connected || mqtt == null) {
            appendLog("请先连接 MQTT");
            return;
        }
        processor = new VideoProcessor(mqtt);
        processor.setStatusListener(msg -> SwingUtilities.invokeLater(() -> appendLog(msg)));
        processor.setStatsListener((packets, frames, decodeFps, displayFps, lost) ->
                SwingUtilities.invokeLater(() -> debugOverlay.setStats(String.format(
                        "包: %d | 帧: %d | 解码FPS: %.1f | 显示FPS: %.1f | 丢包: %d",
                        packets, frames, decodeFps, displayFps, lost))));
        processor.start();

        // 渲染定时器：定时取最新帧刷新显示（对应 QTimer）
        renderTimer = new Timer(Constants.RENDER_INTERVAL_MS, e -> {
            BufferedImage frame = processor.takeLatestFrameForRender();
            if (frame != null) {
                videoPanel.setImage(frame);
            }
        });
        renderTimer.start();

        controlMenu.btnStart.setEnabled(false);
        controlMenu.btnStop.setEnabled(true);
        setStatus("解码中...");
        appendLog("视频处理线程已启动");
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
        controlMenu.btnStart.setEnabled(connected);
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

    // ==== 显示与日志 ====

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
        if (mqtt != null) {
            mqtt.disconnect();
        }
        dispose();
        System.exit(0);
    }

    /** 分层根容器：让所有叠加层始终铺满窗口。 */
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

    /** 视频显示面板：按比例缩放居中绘制最新帧。 */
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
            // 保持宽高比缩放
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
