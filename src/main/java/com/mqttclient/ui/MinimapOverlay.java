package com.mqttclient.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Minimap overlay -- renders the robot's 2D position (x, y) and heading (yaw), scaled to fit,
 * onto a field plan in the bottom-right corner.
 *
 * <p>Interactions:
 * <ul>
 *   <li>Drag the handle in the top-left corner -- proportionally resize the minimap (aspect ratio
 *       locked to the real field)</li>
 *   <li>Mouse wheel over the minimap / calling {@link #zoomIn()} {@link #zoomOut()} -- zoom</li>
 *   <li>Click the title bar -- toggle fullscreen / restore</li>
 *   <li>Click on the map -- drop a ping</li>
 * </ul>
 *
 * <p><b>Swapping maps:</b> call {@link #setMap(MapModel)} with a new {@link MapModel} to change the
 * field size / coordinate mapping / background image. If a real field image is obtained later,
 * simply carry it in {@code MapModel}'s {@code background}; no other logic needs to change.
 *
 * <p>This component fills the whole window but is transparent: by overriding {@link #contains(int, int)},
 * it only "consumes" mouse events inside the minimap rectangle; clicks elsewhere fall through to the
 * layer below (the video layer).
 */
public class MinimapOverlay extends JPanel {

    // ==== Colors ====
    private static final Color PANEL_BG   = new Color(0, 0, 0, 155);
    private static final Color BORDER     = new Color(0x40c4ff);
    private static final Color MAP_FILL   = new Color(0x1b, 0x2a, 0x38, 210);
    private static final Color GRID       = new Color(64, 196, 255, 40);
    private static final Color ROBOT      = new Color(0xff5252);
    private static final Color ROBOT_EDGE = new Color(0xffffff);
    private static final Color TITLE      = new Color(0x40c4ff);
    private static final Color TEXT       = new Color(0xe0e0e0);
    private static final Color TEXT_DIM   = new Color(0x90caf9);
    // Ping colors: neon cyan, sci-fi HUD style
    private static final Color PING       = new Color(0x00e5ff);
    private static final Color PING_SOLID = new Color(0x00e5ff);
    private static final long PING_DURATION_MS = 3000;

    private static final Font FONT       = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font FONT_BOLD  = new Font(Font.MONOSPACED, Font.BOLD, 13);

    // ==== Layout constants ====
    private static final int PAD      = 12;   // Panel padding
    private static final int TITLE_H  = 22;   // Top title bar height
    private static final int FOOTER_H = 20;   // Bottom coordinate readout height
    private static final int HANDLE   = 18;   // Top-left resize handle size
    private static final int MIN_MAP_W = 120; // Minimum minimap width in pixels
    private static final int ZOOM_STEP = 28;  // Pixel step per zoom level (matches 28:15)

    /**
     * Map model: describes the real field size, the mapping from robot coordinates to the map,
     * the heading convention, and an (optional) background image. Immutable; replaced wholesale
     * when the map changes.
     */
    public static final class MapModel {
        /** Map name (shown in the title). */
        public final String name;
        /** Real field length along X, in meters. */
        public final double widthMeters;
        /** Real field width along Y, in meters. */
        public final double heightMeters;
        /** Offset (meters) of the robot coordinate origin relative to the map's bottom-left corner. */
        public final double originOffsetX;
        public final double originOffsetY;
        /** Whether yaw is in degrees (true) or radians (false). Defaults to radians. */
        public final boolean yawInDegrees;
        /** Heading zero-point correction (radians), added to the incoming yaw to align robot conventions. */
        public final double yawOffsetRad;
        /** Optional background image; if null a solid-color rectangle is drawn. Stretched to fill the map rect. */
        public final BufferedImage background;

        /** Convenience constructor: origin at bottom-left, yaw in radians CCW from +X, no background. */
        public MapModel(String name, double widthMeters, double heightMeters) {
            this(name, widthMeters, heightMeters, 0, 0, false, 0, null);
        }

        /** Full constructor with customizable coordinate mapping and background. */
        public MapModel(String name, double widthMeters, double heightMeters,
                        double originOffsetX, double originOffsetY,
                        boolean yawInDegrees, double yawOffsetRad, BufferedImage background) {
            this.name = name;
            this.widthMeters = widthMeters;
            this.heightMeters = heightMeters;
            this.originOffsetX = originOffsetX;
            this.originOffsetY = originOffsetY;
            this.yawInDegrees = yawInDegrees;
            this.yawOffsetRad = yawOffsetRad;
            this.background = background;
        }
    }

    /** Default field: 28m long x 15m wide. */
    public static final MapModel DEFAULT_MAP = new MapModel("场地", 28.0, 15.0);


    private static final class Pose {
        final double x, y, yawRad;
        Pose(double x, double y, double yawRad) {
            this.x = x;
            this.y = y;
            this.yawRad = yawRad;
        }
    }

    /** A single ping ripple effect (world coordinates + sender). */
    private static final class PingEffect {
        final double x, y;
        final String sender;
        final long startMs;
        final long durationMs;
        PingEffect(double x, double y, String sender, long startMs, long durationMs) {
            this.x = x;
            this.y = y;
            this.sender = sender;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    /** One layout computation result: panel rect, map rect, title bar, and resize handle rect. */
    private static final class Layout {
        final Rectangle panel;
        final int mapX, mapY, mapW, mapH;
        final Rectangle titleBar;   // Top title bar: click toggles fullscreen / exit
        final Rectangle handle;
        Layout(Rectangle panel, int mapX, int mapY, int mapW, int mapH,
               Rectangle titleBar, Rectangle handle) {
            this.panel = panel;
            this.mapX = mapX;
            this.mapY = mapY;
            this.mapW = mapW;
            this.mapH = mapH;
            this.titleBar = titleBar;
            this.handle = handle;
        }
    }

    // ==== Configurable appearance ====
    private int minimapWidthPx = 300;   // Minimap width in pixels (height follows the field aspect ratio)
    private int marginPx = 20;          // Margin from the bottom-right window edge

    // ==== State ====
    private volatile MapModel map = DEFAULT_MAP;
    private volatile Pose pose = null;  // null means no position data yet
    private boolean fullscreen = false;

    // Drag-to-resize state
    private boolean dragging = false;
    private int dragStartWidth;
    private java.awt.Point dragStartPoint;

    // ==== Ping state ====
    private BiConsumer<Double, Double> pingListener;              // Callback with world coords on map click
    private final List<PingEffect> pings = new CopyOnWriteArrayList<>();
    private Timer pingAnimTimer;                                  // Drives the ripple animation

    public MinimapOverlay() {
        setOpaque(false);
        installMouseInteraction();
    }

    // ==== Map swap API ====

    /** Replaces the map (field size / coordinate mapping / background). Passing null restores the default field. */
    public void setMap(MapModel map) {
        this.map = (map == null) ? DEFAULT_MAP : map;
        repaint();
    }

    public MapModel getMap() {
        return map;
    }

    /** Sets the minimap width in pixels (height follows the field aspect ratio). */
    public void setMinimapWidth(int px) {
        this.minimapWidthPx = clampWidth(px, getWidth(), getHeight());
        repaint();
    }


    /** Zooms in one level. */
    public void zoomIn() {
        zoomBy(1);
    }

    /** Zooms out one level. */
    public void zoomOut() {
        zoomBy(-1);
    }

    /** Zooms by a number of levels (positive = in, negative = out). Ignored in fullscreen. */
    public void zoomBy(int steps) {
        if (fullscreen) {
            return;
        }
        minimapWidthPx = clampWidth(minimapWidthPx + steps * ZOOM_STEP, getWidth(), getHeight());
        repaint();
    }

    /** Toggles fullscreen / restore. */
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        repaint();
    }

    public void setFullscreen(boolean fs) {
        fullscreen = fs;
        repaint();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    // ==== Data input ====

    /**
     * Sets the robot pose. Thread-safe.
     *
     * @param x   world coordinate X (meters)
     * @param y   world coordinate Y (meters)
     * @param yaw heading; units (degrees / radians) determined by {@link MapModel#yawInDegrees}
     */
    public void setRobotPosition(double x, double y, double yaw) {
        MapModel m = map;
        double yawRad = m.yawInDegrees ? Math.toRadians(yaw) : yaw;
        yawRad += m.yawOffsetRad;
        this.pose = new Pose(x, y, yawRad);
        repaint();
    }

    /**
     * Sets the robot pose (accepts a robotId for caller compatibility; the ID is ignored). Thread-safe.
     */
    public void setRobotPosition(double x, double y, double yaw, int robotId) {
        setRobotPosition(x, y, yaw);
    }

    /** Clears the robot position (back to the "waiting for position" state). */
    public void clearRobotPosition() {
        this.pose = null;
        repaint();
    }

    // ==== Pings ====

    /** Registers a ping callback: invoked with world coordinates (x, y) when the minimap is clicked. */
    public void setOnPingListener(BiConsumer<Double, Double> listener) {
        this.pingListener = listener;
    }

    /**
     * Shows a ping ripple. Thread-safe (auto-switches to the EDT).
     *
     * @param x      world coordinate X (meters)
     * @param y      world coordinate Y (meters)
     * @param sender sender client ID (may be null/empty)
     */
    public void showPing(double x, double y, String sender) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> showPing(x, y, sender));
            return;
        }
        pings.add(new PingEffect(x, y, sender, System.currentTimeMillis(), PING_DURATION_MS));
        ensureAnimTimer();
    }

    /** Starts the animation timer if it is not already running; stops it automatically when no pings remain. */
    private void ensureAnimTimer() {
        if (pingAnimTimer != null && pingAnimTimer.isRunning()) {
            return;
        }
        pingAnimTimer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            pings.removeIf(pe -> now - pe.startMs > pe.durationMs);
            if (pings.isEmpty()) {
                pingAnimTimer.stop();
            } else {
                repaint();
            }
        });
        pingAnimTimer.start();
    }

    /** Converts a click inside the map to world coordinates and invokes the ping callback (no debounce). */
    private void pingAt(java.awt.Point p) {
        if (pingListener == null) {
            return;
        }
        Layout l = computeLayout(getWidth(), getHeight());
        MapModel m = map;
        double fx = clamp01((p.x - l.mapX) / (double) l.mapW);
        double fy = clamp01(1 - (p.y - l.mapY) / (double) l.mapH); // Y-axis flip
        double wx = fx * m.widthMeters - m.originOffsetX;
        double wy = fy * m.heightMeters - m.originOffsetY;
        pingListener.accept(wx, wy);
    }

    // ==== Mouse interaction ====

    private void installMouseInteraction() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (fullscreen) {
                    return;
                }
                Layout l = computeLayout(getWidth(), getHeight());
                if (l.handle != null && l.handle.contains(e.getPoint())) {
                    dragging = true;
                    dragStartWidth = minimapWidthPx;
                    dragStartPoint = e.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!dragging) {
                    return;
                }
                // Handle is at the top-left while the panel is anchored bottom-right:
                // dragging up/left grows the map.
                int dx = dragStartPoint.x - e.getX();
                int dy = dragStartPoint.y - e.getY();
                int delta = Math.max(dx, dy);
                minimapWidthPx = clampWidth(dragStartWidth + delta, getWidth(), getHeight());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                java.awt.Point p = e.getPoint();
                Layout l = computeLayout(getWidth(), getHeight());
                // Inside the map rect -> ping (any number of times; rapid clicks still just ping)
                if (p.x >= l.mapX && p.x < l.mapX + l.mapW
                        && p.y >= l.mapY && p.y < l.mapY + l.mapH) {
                    pingAt(p);
                    return;
                }
                // Title bar -> toggle fullscreen / exit the minimap
                if (l.titleBar != null && l.titleBar.contains(p)) {
                    toggleFullscreen();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (fullscreen) {
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                Layout l = computeLayout(getWidth(), getHeight());
                boolean onHandle = l.handle != null && l.handle.contains(e.getPoint());
                setCursor(onHandle
                        ? Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                        : Cursor.getDefaultCursor());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (fullscreen) {
                    return;
                }
                Layout l = computeLayout(getWidth(), getHeight());
                if (l.panel.contains(e.getPoint())) {
                    zoomBy(-e.getWheelRotation()); // scroll up = zoom in
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    /** "Consumes" mouse events only inside the minimap panel; other areas pass through. In fullscreen it covers the window. */
    @Override
    public boolean contains(int x, int y) {
        if (fullscreen) {
            return true;
        }
        Layout l = computeLayout(getWidth(), getHeight());
        return l.panel.contains(x, y);
    }

    // ==== Layout computation ====

    private Layout computeLayout(int w, int h) {
        MapModel m = map;
        double ratio = m.heightMeters / m.widthMeters;

        if (fullscreen) {
            int maxMapW = w - marginPx * 2 - PAD * 2;
            int maxMapH = h - marginPx * 2 - PAD * 2 - TITLE_H - FOOTER_H;
            int mapW = Math.max(MIN_MAP_W, maxMapW);
            int mapH = (int) Math.round(mapW * ratio);
            if (mapH > maxMapH) {
                mapH = Math.max(1, maxMapH);
                mapW = (int) Math.round(mapH / ratio);
            }
            int panelW = mapW + PAD * 2;
            int panelH = mapH + PAD * 2 + TITLE_H + FOOTER_H;
            int panelX = (w - panelW) / 2;
            int panelY = (h - panelH) / 2;
            Rectangle title = new Rectangle(panelX + PAD, panelY + PAD, mapW, TITLE_H);
            return new Layout(new Rectangle(panelX, panelY, panelW, panelH),
                    panelX + PAD, panelY + PAD + TITLE_H, mapW, mapH, title, null);
        }

        int mapW = clampWidth(minimapWidthPx, w, h);
        int mapH = (int) Math.round(mapW * ratio);
        int panelW = mapW + PAD * 2;
        int panelH = mapH + PAD * 2 + TITLE_H + FOOTER_H;
        int panelX = w - panelW - marginPx;
        int panelY = h - panelH - marginPx;
        Rectangle title = new Rectangle(panelX + PAD, panelY + PAD, mapW, TITLE_H);
        Rectangle handle = new Rectangle(panelX, panelY, HANDLE, HANDLE);
        return new Layout(new Rectangle(panelX, panelY, panelW, panelH),
                panelX + PAD, panelY + PAD + TITLE_H, mapW, mapH, title, handle);
    }

    /** Clamps the desired pixel width to [MIN_MAP_W, the window-fit upper bound]. */
    private int clampWidth(int wpx, int w, int h) {
        double ratio = map.heightMeters / map.widthMeters;
        if (w <= 0 || h <= 0) {
            return Math.max(MIN_MAP_W, wpx);
        }
        int maxByW = w - marginPx - PAD * 2 - 4;
        int maxByH = (int) Math.floor((h - marginPx - PAD * 2 - TITLE_H - FOOTER_H - 4) / ratio);
        int max = Math.max(MIN_MAP_W, Math.min(maxByW, maxByH));
        return Math.max(MIN_MAP_W, Math.min(wpx, max));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        MapModel m = map;
        Pose p = pose;
        Layout L = computeLayout(w, h);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Semi-transparent rounded panel background
        RoundRectangle2D panel = new RoundRectangle2D.Float(
                L.panel.x, L.panel.y, L.panel.width, L.panel.height, 16, 16);
        g2.setColor(PANEL_BG);
        g2.fill(panel);
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.4f));
        g2.draw(panel);

        // Title
        g2.setFont(FONT_BOLD);
        g2.setColor(TITLE);
        String title = String.format("小地图 · %s  %.0f×%.0fm%s",
                m.name, m.widthMeters, m.heightMeters,
                fullscreen ? "  [点击标题还原]" : "");
        g2.drawString(title, L.panel.x + PAD, L.panel.y + PAD + 12);

        // Map rectangle
        drawMapRect(g2, m, L.mapX, L.mapY, L.mapW, L.mapH);

        // Robot marker
        String coordText;
        if (p != null) {
            coordText = drawRobot(g2, m, p, L.mapX, L.mapY, L.mapW, L.mapH);
        } else {
            g2.setFont(FONT);
            g2.setColor(TEXT_DIM);
            String wait = "等待定位...";
            int tw = g2.getFontMetrics().stringWidth(wait);
            g2.drawString(wait, L.mapX + (L.mapW - tw) / 2, L.mapY + L.mapH / 2);
            coordText = "x= --   y= --   yaw= --";
        }

        // Ping ripples (drawn above the robot for visibility)
        drawPings(g2, m, L.mapX, L.mapY, L.mapW, L.mapH);

        // Bottom coordinate readout
        g2.setFont(FONT);
        g2.setColor(TEXT);
        g2.drawString(coordText, L.mapX, L.mapY + L.mapH + 15);

        // Top-left resize handle
        if (L.handle != null) {
            drawGrip(g2, L.handle);
        }

        g2.dispose();
    }

    /** Draws the map rectangle: stretches the background if present, otherwise a solid fill with a 1m grid. */
    private void drawMapRect(Graphics2D g2, MapModel m, int mapX, int mapY, int mapW, int mapH) {
        if (m.background != null) {
            g2.drawImage(m.background, mapX, mapY, mapW, mapH, this);
        } else {
            g2.setColor(MAP_FILL);
            g2.fillRect(mapX, mapY, mapW, mapH);

            // One grid line per meter
            g2.setColor(GRID);
            g2.setStroke(new BasicStroke(1f));
            for (double xm = 1; xm < m.widthMeters; xm += 1) {
                int gx = mapX + (int) Math.round(xm / m.widthMeters * mapW);
                g2.drawLine(gx, mapY, gx, mapY + mapH);
            }
            for (double ym = 1; ym < m.heightMeters; ym += 1) {
                // World Y goes up while screen Y goes down -> flip
                int gy = mapY + mapH - (int) Math.round(ym / m.heightMeters * mapH);
                g2.drawLine(mapX, gy, mapX + mapW, gy);
            }
        }
        // Border
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.6f));
        g2.drawRect(mapX, mapY, mapW, mapH);
    }

    /**
     * Draws the robot position and heading and returns the coordinate readout text.
     *
     * <p>Mapping: world (x, y) meters -> pixels inside the map rectangle. World +X points right and
     * +Y points up; since screen Y points down, the vertical axis is flipped. yaw is in radians,
     * CCW from the +X axis. The marker size scales with the map width.
     *
     * <p>The marker is a <b>teardrop</b> shape with the tip pointing forward.
     */
    private String drawRobot(Graphics2D g2, MapModel m, Pose p, int mapX, int mapY, int mapW, int mapH) {
        double wx = p.x + m.originOffsetX;
        double wy = p.y + m.originOffsetY;

        double fx = clamp01(wx / m.widthMeters);
        double fy = clamp01(wy / m.heightMeters);

        double sx = mapX + fx * mapW;
        double sy = mapY + mapH - fy * mapH;   // Vertical axis flip

        // Marker size adapts to the map width
        double len = Math.max(12, mapW * 0.028);
        double yawDeg = Math.toDegrees(p.yawRad);

        // ---- Teardrop path (tip pointing toward +X) ----
        // Two Bezier curves: tip -> upper bulge -> tail -> lower bulge -> tip
        double screenAngle = -p.yawRad;
        AffineTransform old = g2.getTransform();
        g2.translate(sx, sy);
        g2.rotate(screenAngle);

        Path2D drop = new Path2D.Double();
        drop.moveTo(len, 0);           // Tip
        drop.curveTo(len * 0.1, len * 0.55,
                     -len * 0.6, len * 0.45,
                     -len * 0.35, 0);    // Upper curve to the tail
        drop.curveTo(-len * 0.6, -len * 0.45,
                     len * 0.1, -len * 0.55,
                     len, 0);            // Lower curve back to the tip
        drop.closePath();

        g2.setColor(ROBOT);
        g2.fill(drop);
        g2.setColor(ROBOT_EDGE);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(drop);

        // ---- Center highlight ----
        double r = Math.max(2.5, len * 0.16);
        g2.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));

        g2.setTransform(old);

        return String.format("x=%.2f  y=%.2f  yaw=%.0f°",
                p.x, p.y, yawDeg);
    }

    /**
     * Draws ping ripples: several expanding rings that fade as they spread, plus a center dot and
     * the sender's name.
     */
    private void drawPings(Graphics2D g2, MapModel m, int mapX, int mapY, int mapW, int mapH) {
        if (pings.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        double baseR = Math.max(8, mapW * 0.022);
        for (PingEffect pe : pings) {
            double progress = (now - pe.startMs) / (double) pe.durationMs;
            if (progress < 0 || progress > 1) {
                continue;
            }
            // World -> pixel
            double wx = pe.x + m.originOffsetX;
            double wy = pe.y + m.originOffsetY;
            double fx = clamp01(wx / m.widthMeters);
            double fy = clamp01(wy / m.heightMeters);
            double sx = mapX + fx * mapW;
            double sy = mapY + mapH - fy * mapH;   // Vertical axis flip

            // Expanding rings: 3 of them, phase-offset, growing and fading with progress
            for (int i = 0; i < 3; i++) {
                double rp = progress * 1.35 + i * 0.45;
                if (rp > 1) {
                    continue;
                }
                double radius = baseR * (0.3 + rp * 2.4);
                int alpha = (int) ((1 - rp) * 220);
                g2.setColor(new Color(PING.getRed(), PING.getGreen(), PING.getBlue(),
                        Math.max(0, Math.min(255, alpha))));
                g2.setStroke(new BasicStroke(Math.max(1.5f, (float) (3.5 * (1 - rp)))));
                g2.draw(new Ellipse2D.Double(sx - radius, sy - radius, radius * 2, radius * 2));
            }

            // Center dot
            double r = Math.max(3, baseR * 0.35);
            g2.setColor(PING_SOLID);
            g2.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));

            // Sender name
            if (pe.sender != null && !pe.sender.isEmpty()) {
                g2.setFont(FONT);
                g2.setColor(PING_SOLID);
                g2.drawString(pe.sender, (float) (sx + r + 4), (float) (sy - r - 2));
            }
        }
    }

    /** Top-left resize handle: corner marks + diagonal stripes indicating draggability. */
    private void drawGrip(Graphics2D g2, Rectangle h) {
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawLine(h.x + 3, h.y + 3, h.x + 3, h.y + 13);
        g2.drawLine(h.x + 3, h.y + 3, h.x + 13, h.y + 3);
        g2.drawLine(h.x + 5, h.y + 11, h.x + 11, h.y + 5);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
