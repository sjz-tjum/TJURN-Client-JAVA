package com.mqttclient.ui;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * Ping alert overlay -- sci-fi HUD style. When a ping arrives, a banner pops in at the
 * top of the screen featuring:
 * <ul>
 *   <li>A dark **chamfered-corner** panel with a **neon cyan glowing border** and tick marks</li>
 *   <li>A neon warning-triangle icon + glowing "Attention!" text</li>
 *   <li>A **scan line** sweeping top-to-bottom across the panel</li>
 *   <li>Auto-fades out after about 3 seconds</li>
 * </ul>
 *
 * <p>Fills the whole window, but {@link #contains(int, int)} always returns false so it never
 * intercepts mouse events; clicks pass through to the layers below.
 */
public class PingAlertOverlay extends JPanel {

    private static final Color PANEL_BG    = new Color(6, 18, 30, 228);  // Dark translucent blue-black
    private static final Color NEON        = new Color(0x00e5ff);        // Neon cyan
    private static final Color NEON_BLUE   = new Color(0x0088ff);
    private static final Color WARN_TRIANGLE = new Color(0xff3b30);      // Warning triangle (red)
    private static final Color WARN_GLOW     = new Color(0xff6b60);      // Warning triangle outer glow (bright red)
    private static final Color TEXT_MAIN   = new Color(0xecfeff);

    private static final long DURATION_MS = 3000;
    private static final int BANNER_H = 56;
    private static final int CORNER = 20;   // Chamfer size

    private volatile boolean visible = false;
    private long startMs;
    private Timer timer;

    public PingAlertOverlay() {
        setOpaque(false);
    }

    /** Triggers a one-time "Attention!" alert. Thread-safe (auto-switches to the EDT). */
    public void showAlert(String sender, double x, double y) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showAlert(sender, x, y));
            return;
        }
        this.visible = true;
        this.startMs = System.currentTimeMillis();
        repaint();
        ensureTimer();
    }

    private void ensureTimer() {
        if (timer != null && timer.isRunning()) {
            return;
        }
        timer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            if (now - startMs > DURATION_MS) {
                visible = false;
                timer.stop();
            }
            repaint();
        });
        timer.start();
    }

    /** Does not intercept mouse events; clicks pass through to the layers below. */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!visible) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        double t = (now - startMs) / (double) DURATION_MS;
        if (t > 1) {
            t = 1;
        }
        // Alpha: fade in (first 10%) -> steady -> fade out (last 30%)
        double alpha;
        if (t < 0.1) {
            alpha = t / 0.1;
        } else if (t > 0.7) {
            alpha = (1 - t) / 0.3;
        } else {
            alpha = 1;
        }
        alpha = Math.max(0, Math.min(1, alpha));

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int bw = Math.min(300, w - 40);
        int bx = (w - bw) / 2;
        int by = 36;
        int bh = BANNER_H;

        // ---- Chamfered panel ----
        Path2D panel = angledRect(bx, by, bw, bh, CORNER);
        g2.setColor(new Color(PANEL_BG.getRed(), PANEL_BG.getGreen(), PANEL_BG.getBlue(),
                (int) (PANEL_BG.getAlpha() * alpha)));
        g2.fill(panel);

        // ---- Glowing border (wide outer glow + bright inner line) ----
        g2.setColor(new Color(NEON_BLUE.getRed(), NEON_BLUE.getGreen(), NEON_BLUE.getBlue(),
                (int) (alpha * 60)));
        g2.setStroke(new BasicStroke(6f));
        g2.draw(panel);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 220)));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(panel);

        // ---- Border tick marks (two per side, for a tech look) ----
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 200)));
        g2.setStroke(new BasicStroke(2f));
        for (int i = 1; i <= 2; i++) {
            int yPos = by + bh * i / 3;
            g2.drawLine(bx, yPos, bx + 5, yPos);                 // left
            g2.drawLine(bx + bw - 5, yPos, bx + bw, yPos);       // right
        }

        // ---- Scan line (sweeps top to bottom) ----
        double scan = ((now - startMs) % 1200) / 1200.0;
        int scanY = by + (int) (scan * bh);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 90)));
        g2.fillRect(bx + 2, scanY, bw - 4, 3);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 35)));
        g2.fillRect(bx + 2, scanY + 3, bw - 4, 5);

        // ---- Warning triangle icon ----
        int iconSize = 28;
        int gap = 14;
        String text = "注意！";
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
        int tw = g2.getFontMetrics().stringWidth(text);
        int contentW = iconSize + gap + tw;
        int startX = bx + (bw - contentW) / 2;
        int iconX = startX;
        int iconY = by + (bh - iconSize) / 2;
        int textX = startX + iconSize + gap;
        int textY = by + 37;

        Path2D tri = new Path2D.Double();
        tri.moveTo(iconX + iconSize / 2.0, iconY);
        tri.lineTo(iconX, iconY + iconSize);
        tri.lineTo(iconX + iconSize, iconY + iconSize);
        tri.closePath();
        // Neon glow: wide outer halo + semi-transparent fill + bright red stroke
        g2.setStroke(new BasicStroke(8f));
        g2.setColor(new Color(WARN_GLOW.getRed(), WARN_GLOW.getGreen(), WARN_GLOW.getBlue(),
                (int) (alpha * 70)));
        g2.draw(tri);
        g2.setColor(new Color(WARN_TRIANGLE.getRed(), WARN_TRIANGLE.getGreen(),
                WARN_TRIANGLE.getBlue(), (int) (alpha * 150)));
        g2.fill(tri);
        g2.setStroke(new BasicStroke(2.5f));
        g2.setColor(new Color(WARN_TRIANGLE.getRed(), WARN_TRIANGLE.getGreen(),
                WARN_TRIANGLE.getBlue(), (int) (alpha * 255)));
        g2.draw(tri);
        // Exclamation mark
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(3f));
        int exX = iconX + iconSize / 2;
        g2.drawLine(exX, iconY + 5, exX, iconY + iconSize - 10);
        g2.fill(new Ellipse2D.Double(exX - 2, iconY + iconSize - 6, 4, 4));

        // ---- Glowing "Attention!" text ----
        for (int i = 3; i >= 1; i--) {
            g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                    (int) (alpha * (150 - i * 38))));
            g2.drawString(text, textX + i, textY);
            g2.drawString(text, textX - i, textY);
            g2.drawString(text, textX, textY + i);
            g2.drawString(text, textX, textY - i);
        }
        g2.setColor(TEXT_MAIN);
        g2.drawString(text, textX, textY);

        g2.dispose();
    }

    /** Chamfered-rectangle path (sci-fi panel). */
    private static Path2D angledRect(int x, int y, int w, int h, int c) {
        Path2D p = new Path2D.Double();
        p.moveTo(x, y + c);
        p.lineTo(x + c, y);
        p.lineTo(x + w - c, y);
        p.lineTo(x + w, y + c);
        p.lineTo(x + w, y + h - c);
        p.lineTo(x + w - c, y + h);
        p.lineTo(x + c, y + h);
        p.lineTo(x, y + h - c);
        p.closePath();
        return p;
    }
}
