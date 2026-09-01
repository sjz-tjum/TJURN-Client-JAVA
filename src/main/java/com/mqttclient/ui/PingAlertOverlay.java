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
 * 标点提醒叠加层 —— 科幻 HUD 风格。收到标点时屏幕顶部弹出：
 * <ul>
 *   <li>深色**斜切角**面板 + **霓虹青发光边框** + 边框刻度点</li>
 *   <li>霓虹警告三角图标 + 发光文字「注意！」</li>
 *   <li>一条**扫描线**在面板内自上而下扫过</li>
 *   <li>约 3 秒后淡出自动隐藏</li>
 * </ul>
 *
 * <p>铺满整窗但 {@link #contains(int, int)} 恒为 false，不拦截鼠标事件，点击可穿透。
 */
public class PingAlertOverlay extends JPanel {

    private static final Color PANEL_BG    = new Color(6, 18, 30, 228);  // 深蓝黑半透明
    private static final Color NEON        = new Color(0x00e5ff);        // 霓虹青
    private static final Color NEON_BLUE   = new Color(0x0088ff);
    private static final Color WARN_TRIANGLE = new Color(0xff3b30);      // 警示三角（红）
    private static final Color WARN_GLOW     = new Color(0xff6b60);      // 警示三角外圈辉光（亮红）
    private static final Color TEXT_MAIN   = new Color(0xecfeff);

    private static final long DURATION_MS = 3000;
    private static final int BANNER_H = 56;
    private static final int CORNER = 20;   // 斜切角大小

    private volatile boolean visible = false;
    private long startMs;
    private Timer timer;

    public PingAlertOverlay() {
        setOpaque(false);
    }

    /** 触发一次"注意！"提醒。线程安全（自动切到 EDT）。 */
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

    /** 不拦截鼠标事件，点击穿透到下层。 */
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
        // 透明度：淡入(前10%) → 常亮 → 淡出(后30%)
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

        // ── 斜切角面板 ──
        Path2D panel = angledRect(bx, by, bw, bh, CORNER);
        g2.setColor(new Color(PANEL_BG.getRed(), PANEL_BG.getGreen(), PANEL_BG.getBlue(),
                (int) (PANEL_BG.getAlpha() * alpha)));
        g2.fill(panel);

        // ── 发光边框（外圈宽辉光 + 内圈亮线）──
        g2.setColor(new Color(NEON_BLUE.getRed(), NEON_BLUE.getGreen(), NEON_BLUE.getBlue(),
                (int) (alpha * 60)));
        g2.setStroke(new BasicStroke(6f));
        g2.draw(panel);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 220)));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(panel);

        // ── 边框刻度点（左右两侧各两个，科技感）──
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 200)));
        g2.setStroke(new BasicStroke(2f));
        for (int i = 1; i <= 2; i++) {
            int yPos = by + bh * i / 3;
            g2.drawLine(bx, yPos, bx + 5, yPos);                 // 左
            g2.drawLine(bx + bw - 5, yPos, bx + bw, yPos);       // 右
        }

        // ── 扫描线（自上而下扫过）──
        double scan = ((now - startMs) % 1200) / 1200.0;
        int scanY = by + (int) (scan * bh);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 90)));
        g2.fillRect(bx + 2, scanY, bw - 4, 3);
        g2.setColor(new Color(NEON.getRed(), NEON.getGreen(), NEON.getBlue(),
                (int) (alpha * 35)));
        g2.fillRect(bx + 2, scanY + 3, bw - 4, 5);

        // ── 警告三角图标（霓虹青）──
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
        // 霓虹辉光：外圈宽光晕 + 半透明填充 + 亮红描边
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
        // 感叹号
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(3f));
        int exX = iconX + iconSize / 2;
        g2.drawLine(exX, iconY + 5, exX, iconY + iconSize - 10);
        g2.fill(new Ellipse2D.Double(exX - 2, iconY + iconSize - 6, 4, 4));

        // ── 发光文字「注意！」──
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

    /** 斜切角矩形路径（科幻面板）。 */
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
