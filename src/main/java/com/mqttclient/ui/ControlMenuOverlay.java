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
 * 控制菜单叠加层（类似游戏的 ESC 暂停菜单）。
 *
 * <p>铺满整层，绘制半透明暗化遮罩，中央放一张圆角卡片，卡片内纵向排列
 * 一组 {@link HudButton}。按钮供 {@link MainWindow} 取用以挂接动作与
 * 管理启用状态。点击卡片外区域可关闭（回调由 MainWindow 设置）。
 */
public class ControlMenuOverlay extends JPanel {

    private static final Color SCRIM = new Color(0, 0, 0, 150);

    public final HudButton btnConnect = new HudButton("连接 MQTT");
    public final HudButton btnDisconnect = new HudButton("断开连接");
    public final HudButton btnStart = new HudButton("开始解码");
    public final HudButton btnStop = new HudButton("停止解码");
    public final HudButton btnClearLog = new HudButton("清空日志");
    public final HudButton btnChangeId = new HudButton("修改客户端 ID");

    private final JPanel card;

    /** 点击卡片外遮罩区域时的回调（用于关闭菜单）。 */
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
                btnConnect, btnDisconnect, btnStart, btnStop, btnClearLog, btnChangeId}) {
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(b);
            card.add(Box.createVerticalStrut(12));
        }

        add(card, new GridBagConstraints());

        // 点击卡片外区域关闭；点击卡片内部不冒泡到这里
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

    /** 居中卡片：圆角深色半透明底板 + 发光边框。 */
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
