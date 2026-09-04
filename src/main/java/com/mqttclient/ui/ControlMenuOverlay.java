package com.mqttclient.ui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Control menu overlay (like a game's ESC pause menu).
 *
 * <p>Fills the entire layer, draws a semi-transparent dimming scrim, and centers a rounded
 * card that vertically stacks a set of {@link HudButton}s. The buttons are exposed for
 * {@link MainWindow} to attach actions and manage enabled state. Clicking outside the card
 * closes the menu (callback set by MainWindow).
 */
public class ControlMenuOverlay extends JPanel {

    private static final Color SCRIM = new Color(0, 0, 0, 150);

    public final HudButton btnConnect = new HudButton("连接 MQTT");
    public final HudButton btnDisconnect = new HudButton("断开连接");
    public final HudButton btnStart = new HudButton("开始解码");
    public final HudButton btnStop = new HudButton("停止解码");
    public final HudButton btnClearLog = new HudButton("清空日志");
    public final HudButton btnChangeId = new HudButton("修改客户端 ID");
    public final HudButton btnSwitchSource = new HudButton("切换视频源 (UDP/MQTT)");
    public final HudButton btnReloadConfig = new HudButton("重载配置");

    private final JPanel card;

    /** Callback invoked when the scrim area outside the card is clicked (used to close the menu). */
    private Runnable onScrimClick;

    public ControlMenuOverlay() {
        setOpaque(false);
        setLayout(new GridBagLayout());

        card = new CardPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 34, 28, 34));

        javax.swing.JLabel title = new javax.swing.JLabel("控 制 菜 单", SwingConstants.CENTER);
        title.setForeground(new Color(0x40c4ff));
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(20));

        for (HudButton b : new HudButton[]{
                btnConnect, btnDisconnect, btnStart, btnStop, btnSwitchSource,
                btnReloadConfig, btnClearLog, btnChangeId}) {
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(b);
            card.add(Box.createVerticalStrut(12));
        }

        add(card, new GridBagConstraints());

        // Clicking outside the card closes the menu; clicks inside the card do not bubble here
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!card.getBounds().contains(e.getPoint()) && onScrimClick != null) {
                    onScrimClick.run();
                }
            }
        });
    }

    public void setOnScrimClick(Runnable r) {
        this.onScrimClick = r;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(SCRIM);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }

    /** Centered card: rounded, dark, semi-transparent background with a glowing border. */
    private static class CardPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D bg = new RoundRectangle2D.Float(1, 1, w - 3, h - 3, 24, 24);
            g2.setColor(new Color(0x12, 0x1a, 0x24, 235));
            g2.fill(bg);
            g2.setStroke(new java.awt.BasicStroke(1.6f));
            g2.setColor(new Color(0x40c4ff));
            g2.draw(bg);
            g2.dispose();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }
}
