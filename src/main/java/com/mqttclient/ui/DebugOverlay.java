package com.mqttclient.ui;

import com.mqttclient.config.Constants;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Debug data HUD (similar to Minecraft's F3).
 *
 * <p>A semi-transparent panel floating over the video in the top-left corner, showing:
 * <ul>
 *   <li>Connection info: LED status + status text + client ID + broker host:port + subscribed topics</li>
 *   <li>Statistics: packets / frames / decode FPS / display FPS / dropped packets</li>
 *   <li>Live log: embedded semi-transparent scrolling text area</li>
 * </ul>
 *
 * <p>Data is written by {@link MainWindow} through setters that trigger a repaint; visibility
 * is controlled by MainWindow based on the F3 (temporary) / F4 (pinned) state.
 */
public class DebugOverlay extends JPanel {

    private static final Color PANEL_BG = new Color(0, 0, 0, 155);
    private static final Color TITLE_COLOR = new Color(0x40c4ff);
    private static final Color LABEL_COLOR = new Color(0x90caf9);
    private static final Color VALUE_COLOR = new Color(0xe0e0e0);
    private static final Color STATS_COLOR = new Color(0xa5d6a5);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 14);

    // Data model
    private boolean connected = false;
    private String status = "未连接";
    private String clientId = "";
    private String stats = "包: 0 | 帧: 0 | FPS: 0.0";
    private String sourceLabel = "MQTT";
    private boolean pinned = false;

    // Embedded log area
    private final JTextArea logArea = new JTextArea();
    private final JScrollPane logScroll;

    public DebugOverlay() {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(javax.swing.BorderFactory.createEmptyBorder(238, 14, 14, 14));

        logArea.setEditable(false);
        logArea.setOpaque(false);
        logArea.setForeground(new Color(0xb9f6ca));
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        logScroll = new JScrollPane(logArea);
        logScroll.setOpaque(false);
        logScroll.getViewport().setOpaque(false);
        logScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setPreferredSize(new Dimension(420, 200));
        add(logScroll, BorderLayout.CENTER);
    }

    /** Lets MainWindow append log messages directly. */
    public JTextArea getLogArea() {
        return logArea;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        repaint();
    }

    public void setStatus(String status) {
        this.status = status;
        repaint();
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
        repaint();
    }

    public void setStats(String stats) {
        this.stats = stats;
        repaint();
    }

    public void setSourceLabel(String label) {
        this.sourceLabel = label;
        repaint();
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int panelW = 448;
        int panelH = getHeight() - 20;
        RoundRectangle2D bg = new RoundRectangle2D.Float(10, 10, panelW, panelH, 18, 18);
        g2.setColor(PANEL_BG);
        g2.fill(bg);

        int x = 28;
        int y = 40;

        // Title
        g2.setFont(MONO_BOLD.deriveFont(16f));
        g2.setColor(TITLE_COLOR);
        g2.drawString("视频接收器 [" + sourceLabel + "]"
                + (pinned ? " [常驻]" : ""), x, y);
        y += 28;

        // Connection info
        g2.setFont(MONO_BOLD);
        g2.setColor(LABEL_COLOR);
        g2.drawString("── 连接 ──", x, y);
        y += 22;

        g2.setFont(MONO);
        // LED
        g2.setColor(connected ? new Color(0x43a047) : new Color(0xe53935));
        g2.drawString("●", x, y);
        g2.setColor(VALUE_COLOR);
        g2.drawString("  状态: " + status, x + 6, y);
        y += 20;
        drawKv(g2, x, y, "客户端ID", clientId.isEmpty() ? "(未设置)" : clientId);
        y += 20;
        drawKv(g2, x, y, "Broker",
                Constants.DEFAULT_BROKER_HOST + ":" + Constants.DEFAULT_BROKER_PORT);
        y += 20;
        drawKv(g2, x, y, "订阅主题", String.join(", ", Constants.SUBSCRIBE_TOPICS));
        y += 28;

        // Statistics
        g2.setFont(MONO_BOLD);
        g2.setColor(LABEL_COLOR);
        g2.drawString("── 统计 ──", x, y);
        y += 22;
        g2.setFont(MONO);
        g2.setColor(STATS_COLOR);
        g2.drawString(stats, x, y);
        y += 26;

        // Log title
        g2.setFont(MONO_BOLD);
        g2.setColor(LABEL_COLOR);
        g2.drawString("── 日志 ──", x, y);

        g2.dispose();
    }

    private void drawKv(Graphics2D g2, int x, int y, String key, String value) {
        g2.setColor(LABEL_COLOR);
        String k = key + ": ";
        g2.drawString(k, x, y);
        int kw = g2.getFontMetrics().stringWidth(k);
        g2.setColor(VALUE_COLOR);
        g2.drawString(value, x + kw, y);
    }
}
