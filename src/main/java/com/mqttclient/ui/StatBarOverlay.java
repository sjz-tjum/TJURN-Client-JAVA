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
 * Bottom-left status panel -- a game-HUD-style health bar.
 *
 * <p>Shows the robot type + level as a title above three vertically stacked colored bars
 * (health / heat / experience), each with a "current/max" label inside.
 * Data is written by {@link MainWindow} through setters that trigger a repaint.
 */
public class StatBarOverlay extends JPanel {

    // ---- Colors ----
    private static final Color PANEL_BG    = new Color(0, 0, 0, 155);
    private static final Color TITLE_TEXT  = new Color(0x40c4ff);
    private static final Color LEVEL_TEXT  = new Color(0xffd740);
    private static final Color BAR_BG      = new Color(0x1a, 0x1a, 0x2e, 200);

    // Bar colors, each defined as an (empty -> full) gradient
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

    // ---- Fonts ----
    private static final Font FONT_TITLE   = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font FONT_BAR     = new Font(Font.MONOSPACED, Font.BOLD, 12);

    // ---- Layout constants ----
    private static final int PAD          = 14;
    private static final int TITLE_H      = 32;   // Title area height (incl. divider)
    private static final int BAR_H        = 18;   // Height of each bar
    private static final int BAR_GAP      = 8;    // Gap between bars
    private static final int PANEL_W      = 320;  // Fixed panel width
    private static final int PANEL_MARGIN = 14;   // Margin from the window edge

    // ---- Robot type name mapping ----
    private static final String[] ROBOT_TYPE_NAMES = {
        "",               // 0
        "步兵",           // 1
        "工程",           // 2
        "哨兵",           // 3
        "英雄",           // 4
        "空中",           // 5
    };

    // ---- Data model ----
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

    // ==== Data updates (thread-safe: called on the EDT) ====

    /** Update the static status (robot_type / level / max_health / max_heat). */
    public void setStaticStatus(int robotType, int level, int maxHealth, int maxHeat) {
        this.robotType = clamp(robotType, 0, ROBOT_TYPE_NAMES.length - 1);
        this.level = level;
        this.maxHealth = maxHealth > 0 ? maxHealth : 1;
        this.maxHeat = maxHeat > 0 ? maxHeat : 1;
        repaint();
    }

    /** Update the dynamic status (current_health / current_heat / current_experience / experience_for_upgrade). */
    public void setDynamicStatus(int currentHealth, float currentHeat,
                                  int currentExperience, int experienceForUpgrade) {
        this.currentHealth = currentHealth;
        this.currentHeat = currentHeat;
        this.currentExperience = currentExperience;
        this.experienceForUpgrade = experienceForUpgrade > 0 ? experienceForUpgrade : 1;
        repaint();
    }

    // ==== Painting ====

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

        // Panel rectangle (anchored to the bottom)
        int panelH = computePanelHeight();
        int panelX = PANEL_MARGIN;
        int panelY = h - panelH - PANEL_MARGIN;

        // ---- Panel background ----
        RoundRectangle2D bg = new RoundRectangle2D.Float(
                panelX, panelY, PANEL_W, panelH, 16, 16);
        g2.setColor(PANEL_BG);
        g2.fill(bg);

        int cx = panelX + PAD;           // content X
        int barW = PANEL_W - PAD * 2;    // Bar width

        // ---- Title row ----
        int titleY = panelY + PAD;
        g2.setFont(FONT_TITLE);

        // Robot type name
        String typeName = getRobotTypeName();
        g2.setColor(TITLE_TEXT);
        g2.drawString(typeName, cx, titleY + 22);

        // Level
        String lvText = "Lv." + level;
        int lvW = g2.getFontMetrics().stringWidth(lvText);
        g2.setColor(LEVEL_TEXT);
        g2.drawString(lvText, cx + barW - lvW, titleY + 22);

        // Title divider
        int dividerY = titleY + TITLE_H - 4;
        g2.setColor(DIVIDER);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(cx, dividerY, cx + barW, dividerY);

        // ---- Three progress bars ----
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
     * Draws a single status bar and returns the Y coordinate for the next bar.
     *
     * @param label   Emoji label (e.g. "❤️")
     * @param cur     current value
     * @param max     maximum value
     */
    private int drawBar(Graphics2D g2, int x, int y, int w,
                        String label, int cur, int max,
                        Color emptyColor, Color fullColor, Color glowColor) {
        float ratio = max > 0 ? clamp01((float) cur / max) : 0f;

        // Background slot
        RoundRectangle2D slot = new RoundRectangle2D.Float(x, y, w, BAR_H, 10, 10);
        g2.setColor(BAR_BG);
        g2.fill(slot);

        // Fill portion
        int fillW = Math.max(4, Math.round(w * ratio));
        RoundRectangle2D fill = new RoundRectangle2D.Float(x, y, fillW, BAR_H, 10, 10);
        g2.setColor(fullColor);
        g2.fill(fill);

        // Glow effect: gradient halo on the right side
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

        // Border
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(fullColor.darker());
        g2.draw(slot);

        // ---- Inner text: label + current/max ----
        g2.setFont(FONT_BAR);
        FontRenderContext frc = g2.getFontRenderContext();

        String leftText = label;
        String rightText = formatNum(cur) + " / " + formatNum(max);

        int leftW = (int) g2.getFont().getStringBounds(leftText, frc).getWidth();
        int rightW = (int) g2.getFont().getStringBounds(rightText, frc).getWidth();

        // If the fill is too narrow for both texts, center the value
        int centerX = x + w / 2;
        int textY = y + BAR_H / 2 + 5;

        g2.setStroke(new BasicStroke(0f)); // reset

        if (fillW > leftW + rightW + 24) {
            // Both fit inside the fill: left-aligned label, right-aligned value
            drawBarText(g2, leftText, x + 10, textY, true);
            drawBarText(g2, rightText, x + w - rightW - 10, textY, true);
        } else if (fillW > rightW + 16) {
            // Only the value fits inside the fill
            drawBarText(g2, rightText, centerX - rightW / 2, textY, true);
        } else {
            // Fill too small; draw white text outside the fill
            drawBarText(g2, rightText, centerX - rightW / 2, textY, false);
        }

        return y + BAR_H + BAR_GAP;
    }

    /** Draws text inside a progress bar (with a drop shadow). */
    private void drawBarText(Graphics2D g2, String text, int x, int y, boolean inFill) {
        // Shadow
        g2.setColor(BAR_TEXT_SHADOW);
        g2.drawString(text, x + 1, y + 1);
        // Main text
        g2.setColor(inFill ? Color.WHITE : BAR_TEXT);
        g2.drawString(text, x, y);
    }

    /** Formats a number: 1000 -> 1,000 */
    private static String formatNum(int n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    /** Returns the robot type name. */
    private String getRobotTypeName() {
        if (robotType >= 0 && robotType < ROBOT_TYPE_NAMES.length) {
            String name = ROBOT_TYPE_NAMES[robotType];
            return name.isEmpty() ? "未知" : name;
        }
        return "未知 " + robotType;
    }

    /** Computes the total panel height. */
    private int computePanelHeight() {
        return PAD + TITLE_H + 8 + (BAR_H + BAR_GAP) * 3 + PAD - BAR_GAP;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PANEL_W + PANEL_MARGIN * 2, computePanelHeight() + PANEL_MARGIN * 2);
    }

    // ==== Utility methods ====

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static float clamp01(float v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
