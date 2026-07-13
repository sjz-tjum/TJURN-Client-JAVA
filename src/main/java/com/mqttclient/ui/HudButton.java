package com.mqttclient.ui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * 游戏风格自绘按钮。
 *
 * <p>圆角矩形背景，正常 / 悬停 / 按下三态渐变（科技蓝青色调），
 * 悬停时描一圈发光边，禁用时整体变暗。文字白色粗体居中。
 * 全部使用 Java2D 自绘，不依赖 LookAndFeel。
 */
public class HudButton extends JButton {

    // 三态渐变色（上 -> 下）
    private static final Color NORMAL_TOP = new Color(0x1565c0);
    private static final Color NORMAL_BOTTOM = new Color(0x0d47a1);
    private static final Color HOVER_TOP = new Color(0x29b6f6);
    private static final Color HOVER_BOTTOM = new Color(0x1976d2);
    private static final Color PRESSED_TOP = new Color(0x0d47a1);
    private static final Color PRESSED_BOTTOM = new Color(0x083373);
    private static final Color DISABLED_TOP = new Color(0x37474f);
    private static final Color DISABLED_BOTTOM = new Color(0x263238);

    private static final Color GLOW = new Color(0x40c4ff);
    private static final Color BORDER = new Color(0x64b5f6);

    private static final int ARC = 16;

    public HudButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setForeground(Color.WHITE);
        setFont(getFont().deriveFont(java.awt.Font.BOLD, 15f));
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        // rollover 状态变化时重绘
        setRolloverEnabled(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        boolean enabled = isEnabled();
        boolean pressed = getModel().isPressed();
        boolean rollover = getModel().isRollover();

        Color top, bottom;
        if (!enabled) {
            top = DISABLED_TOP;
            bottom = DISABLED_BOTTOM;
        } else if (pressed) {
            top = PRESSED_TOP;
            bottom = PRESSED_BOTTOM;
        } else if (rollover) {
            top = HOVER_TOP;
            bottom = HOVER_BOTTOM;
        } else {
            top = NORMAL_TOP;
            bottom = NORMAL_BOTTOM;
        }

        RoundRectangle2D shape = new RoundRectangle2D.Float(1, 1, w - 3, h - 3, ARC, ARC);

        // 悬停发光外圈
        if (enabled && rollover) {
            g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 90));
            g2.setStroke(new java.awt.BasicStroke(3f));
            g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, ARC + 2, ARC + 2));
        }

        // 渐变填充
        g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
        g2.fill(shape);

        // 边框
        g2.setStroke(new java.awt.BasicStroke(1.2f));
        g2.setColor(enabled ? BORDER : new Color(0x455a64));
        g2.draw(shape);

        // 文字
        g2.setColor(enabled ? Color.WHITE : new Color(0x90a4ae));
        g2.setFont(getFont());
        java.awt.FontMetrics fm = g2.getFontMetrics();
        String text = getText();
        int tx = (w - fm.stringWidth(text)) / 2;
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, tx, ty);

        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 240), 46);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}

