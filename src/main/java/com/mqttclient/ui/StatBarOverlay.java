package com.mqttclient.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.RoundRectangle2D;

/**
 * 左下角状态面板 —— 仿游戏 HUD 血条风格。
 *
 * <p>显示机器人类型 + 等级作为标题，下方纵向排列三条彩色进度条
 * （血量 / 热量 / 经验），每一条内部嵌有"当前值/最大值"文字。
 * 数据由 {@link MainWindow} 通过 setter 写入后触发重绘。
 */
public class StatBarOverlay extends JPanel {

    // ── 配色 ──
    private static final Color PANEL_BG    = new Color(0, 0, 0, 155);
    private static final Color TITLE_TEXT  = new Color(0x40c4ff);
    private static final Color LEVEL_TEXT  = new Color(0xffd740);
    private static final Color BAR_BG      = new Color(0x1a, 0x1a, 0x2e, 200);

    // 三条进度条配色 (空色 → 满色 渐变)
    private static final Color HP_EMPTY    = new Color(0x4a, 0x00, 0x00);
    private static final Color HP_FULL     = new Color(0xe5, 0x39, 0x35);
    private static final Color HP_GLOW     = new Color(0xff, 0x52, 0x52, 120);

    private static final Color HEAT_EMPTY  = new Color(0x4a, 0x2e, 0x00);
    private static final Color HEAT_FULL   = new Color(0xff, 0x8f, 0x00);
    private static final Color HEAT_GLOW   = new Color(0xff, 0xb7, 0x4d, 100);

    private static final Color XP_EMPTY    = new Color(0x00, 0x2a, 0x4a);
    private static final Color XP_FULL     = new Color(0x1e, 0x88, 0xe5);
    private static final Color XP_GLOW     = new Color(0x42, 0xa5, 0xf5, 100);

    private static final Color BAR_TEXT    = new Color(0xff, 0xff, 0xff, 220);
    private static final Color BAR_TEXT_SHADOW = new Color(0, 0, 0, 120);
    private static final Color DIVIDER     = new Color(0x40, 0xc4, 0xff, 50);

    // ── 字体 ──
    private static final Font FONT_TITLE   = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font FONT_BAR     = new Font(Font.MONOSPACED, Font.BOLD, 12);

    // ── 布局常量 ──
    private static final int PAD          = 14;
    private static final int TITLE_H      = 32;   // 标题区高度（含分割线）
    private static final int BAR_H        = 18;   // 每一条进度条高度
    private static final int BAR_GAP      = 8;    // 条与条间距
    private static final int PANEL_W      = 320;  // 面板固定宽度
    private static final int PANEL_MARGIN = 14;   // 距窗口边缘

    // ── 机器人类型名称映射 ──
    private static final String[] ROBOT_TYPE_NAMES = {
        "",               // 0
        "步兵",           // 1
        "工程",           // 2
        "哨兵",           // 3
        "英雄",           // 4
        "空中",           // 5
    };

    // ── 数据模型 ──
    private int robotType = 0;
    private int level = 0;
    private int currentHealth = 0;
    private int maxHealth = 100;
    private float currentHeat = 0;
    private int maxHeat = 100;
    private int currentExperience = 0;
    private int experienceForUpgrade = 100;

    public StatBarOverlay() {
        setOpaque(false);
    }

    // ==== 数据更新（线程安全：EDT 调用） ====

    /** 更新静态状态（robot_type / level / max_health / max_heat）。 */
    public void setStaticStatus(int robotType, int level, int maxHealth, int maxHeat) {
        this.robotType = clamp(robotType, 0, ROBOT_TYPE_NAMES.length - 1);
        this.level = level;
        this.maxHealth = maxHealth > 0 ? maxHealth : 1;
        this.maxHeat = maxHeat > 0 ? maxHeat : 1;
        repaint();
    }

    /** 更新动态状态（current_health / current_heat / current_experience / experience_for_upgrade）。 */
    public void setDynamicStatus(int currentHealth, float currentHeat,
                                  int currentExperience, int experienceForUpgrade) {
        this.currentHealth = currentHealth;
        this.currentHeat = currentHeat;
        this.currentExperience = currentExperience;
        this.experienceForUpgrade = experienceForUpgrade > 0 ? experienceForUpgrade : 1;
        repaint();
    }

    // ==== 绘制 ====

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 面板矩形（从底部算起）
        int panelH = computePanelHeight();
        int panelX = PANEL_MARGIN;
        int panelY = h - panelH - PANEL_MARGIN;

        // ── 面板背景 ──
        RoundRectangle2D bg = new RoundRectangle2D.Float(
                panelX, panelY, PANEL_W, panelH, 16, 16);
        g2.setColor(PANEL_BG);
        g2.fill(bg);

        int cx = panelX + PAD;           // content X
        int barW = PANEL_W - PAD * 2;    // 进度条宽度

        // ── 标题行 ──
        int titleY = panelY + PAD;
        g2.setFont(FONT_TITLE);

        // 机器人类型名称
        String typeName = getRobotTypeName();
        g2.setColor(TITLE_TEXT);
        g2.drawString(typeName, cx, titleY + 22);

        // 等级
        String lvText = "Lv." + level;
        int lvW = g2.getFontMetrics().stringWidth(lvText);
        g2.setColor(LEVEL_TEXT);
        g2.drawString(lvText, cx + barW - lvW, titleY + 22);

        // 标题分割线
        int dividerY = titleY + TITLE_H - 4;
        g2.setColor(DIVIDER);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(cx, dividerY, cx + barW, dividerY);

        // ── 三条进度条 ──
        int barY = dividerY + 8;

        barY = drawBar(g2, cx, barY, barW,
                "❤️", currentHealth, maxHealth,
                HP_EMPTY, HP_FULL, HP_GLOW);

        barY = drawBar(g2, cx, barY, barW,
                "🔥", (int) currentHeat, maxHeat,
                HEAT_EMPTY, HEAT_FULL, HEAT_GLOW);

        drawBar(g2, cx, barY, barW,
                "⭐", currentExperience, experienceForUpgrade,
                XP_EMPTY, XP_FULL, XP_GLOW);

        g2.dispose();
    }

    /**
     * 绘制单条状态进度条，返回下一个 bar 的 Y 坐标。
     *
     * @param label   Emoji 标签（如 "❤️"）
     * @param cur     当前值
     * @param max     最大值
     */
    private int drawBar(Graphics2D g2, int x, int y, int w,
                        String label, int cur, int max,
                        Color emptyColor, Color fullColor, Color glowColor) {
        float ratio = max > 0 ? clamp01((float) cur / max) : 0f;

        // 背景槽
        RoundRectangle2D slot = new RoundRectangle2D.Float(x, y, w, BAR_H, 10, 10);
        g2.setColor(BAR_BG);
        g2.fill(slot);

        // 填充部分
        int fillW = Math.max(4, Math.round(w * ratio));
        RoundRectangle2D fill = new RoundRectangle2D.Float(x, y, fillW, BAR_H, 10, 10);
        g2.setColor(fullColor);
        g2.fill(fill);

        // 发光效果：右侧渐变光晕
        if (ratio > 0.08f) {
            int glowX = Math.max(x + 4, x + fillW - 40);
            int glowW = Math.min(fillW - 4, 40);
            if (glowW > 0) {
                g2.setPaint(new java.awt.GradientPaint(
                        glowX, y, new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 0),
                        glowX + glowW, y, glowColor));
                g2.fill(new RoundRectangle2D.Float(glowX, y, glowW, BAR_H, 10, 10));
            }
        }

        // 边框
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(fullColor.darker());
        g2.draw(slot);

        // ── 内部文字：标签 + 值/最大值 ──
        g2.setFont(FONT_BAR);
        FontRenderContext frc = g2.getFontRenderContext();

        String leftText = label;
        String rightText = formatNum(cur) + " / " + formatNum(max);

        int leftW = (int) g2.getFont().getStringBounds(leftText, frc).getWidth();
        int rightW = (int) g2.getFont().getStringBounds(rightText, frc).getWidth();

        // 如果填充宽度不足以容纳两侧文字，居中显示右侧数字
        int centerX = x + w / 2;
        int textY = y + BAR_H / 2 + 5;

        g2.setStroke(new BasicStroke(0f)); // reset

        if (fillW > leftW + rightW + 24) {
            // 左右分开显示（都在填充区内）
            // 左对齐标签
            drawBarText(g2, leftText, x + 10, textY, true);
            // 右对齐数值
            drawBarText(g2, rightText, x + w - rightW - 10, textY, true);
        } else if (fillW > rightW + 16) {
            // 只显示右侧数值在填充区内
            drawBarText(g2, rightText, centerX - rightW / 2, textY, true);
        } else {
            // 填充不足，在填充区外显示白色文字
            drawBarText(g2, rightText, centerX - rightW / 2, textY, false);
        }

        return y + BAR_H + BAR_GAP;
    }

    /** 绘制进度条内部文字（带投影）。 */
    private void drawBarText(Graphics2D g2, String text, int x, int y, boolean inFill) {
        // 投影
        g2.setColor(BAR_TEXT_SHADOW);
        g2.drawString(text, x + 1, y + 1);
        // 正文
        g2.setColor(inFill ? Color.WHITE : BAR_TEXT);
        g2.drawString(text, x, y);
    }

    /** 格式化数字：1000 → 1,000 */
    private static String formatNum(int n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    /** 获取机器人类型名称。 */
    private String getRobotTypeName() {
        if (robotType >= 0 && robotType < ROBOT_TYPE_NAMES.length) {
            String name = ROBOT_TYPE_NAMES[robotType];
            return name.isEmpty() ? "未知" : name;
        }
        return "未知 " + robotType;
    }

    /** 计算面板总高度。 */
    private int computePanelHeight() {
        return PAD + TITLE_H + 8 + (BAR_H + BAR_GAP) * 3 + PAD - BAR_GAP;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PANEL_W + PANEL_MARGIN * 2, computePanelHeight() + PANEL_MARGIN * 2);
    }

    // ==== 工具方法 ====

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static float clamp01(float v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
