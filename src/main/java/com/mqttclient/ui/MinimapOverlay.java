// package com.mqttclient.ui;

// import javax.swing.JPanel;
// import java.awt.BasicStroke;
// import java.awt.Color;
// import java.awt.Font;
// import java.awt.Graphics;
// import java.awt.Graphics2D;
// import java.awt.RenderingHints;
// import java.awt.geom.AffineTransform;
// import java.awt.geom.Ellipse2D;
// import java.awt.geom.Path2D;
// import java.awt.geom.RoundRectangle2D;
// import java.awt.image.BufferedImage;

// /**
//  * 小地图叠加层 —— 把机器人的二维坐标 (x, y) 与朝向 (yaw) 等比例标注到右下角的场地平面图上。
//  *
//  * <p>浮在视频之上、位于窗口右下角的半透明面板：
//  * <ul>
//  *   <li>按真实场地尺寸（默认 长 28m × 宽 15m）的宽高比绘制一个矩形代表地图</li>
//  *   <li>机器人坐标 (x, y) 从「世界坐标(米)」等比例映射到矩形内的像素位置</li>
//  *   <li>用一个带方向的三角标记表示机器人当前位置与朝向 yaw</li>
//  * </ul>
//  *
//  * <p><b>更换地图接口：</b>调用 {@link #setMap(MapModel)} 传入新的 {@link MapModel}
//  * 即可切换场地尺寸 / 坐标系映射 / 底图。若日后拿到真实地图图片，只需在
//  * {@code MapModel} 中携带 {@code background} 即可自动绘制到矩形内，其余逻辑无需改动。
//  *
//  * <p>数据由 {@link MainWindow} 通过 {@link #setRobotPosition(double, double, double)}
//  * 写入后触发重绘；该方法线程安全，可从 MQTT 线程直接调用。
//  */
// public class MinimapOverlay extends JPanel {

//     // ==== 配色 ====
//     private static final Color PANEL_BG   = new Color(0, 0, 0, 155);
//     private static final Color BORDER     = new Color(0x40c4ff);
//     private static final Color MAP_FILL   = new Color(0x1b, 0x2a, 0x38, 210);
//     private static final Color GRID       = new Color(64, 196, 255, 40);
//     private static final Color ROBOT      = new Color(0xff5252);
//     private static final Color ROBOT_EDGE = new Color(0xffffff);
//     private static final Color TITLE      = new Color(0x40c4ff);
//     private static final Color TEXT       = new Color(0xe0e0e0);
//     private static final Color TEXT_DIM   = new Color(0x90caf9);

//     private static final Font FONT       = new Font(Font.MONOSPACED, Font.PLAIN, 12);
//     private static final Font FONT_BOLD  = new Font(Font.MONOSPACED, Font.BOLD, 13);

//     /**
//      * 地图模型：描述真实场地尺寸、机器人坐标系到地图的映射关系、朝向约定与（可选）底图。
//      * 不可变对象；更换地图时整体替换。
//      */
//     public static final class MapModel {
//         /** 地图名称（显示在标题上）。 */
//         public final String name;
//         /** 场地在 X 方向（长）的真实长度，单位米。 */
//         public final double widthMeters;
//         /** 场地在 Y 方向（宽）的真实长度，单位米。 */
//         public final double heightMeters;
//         /** 机器人坐标系原点相对「地图左下角」的偏移（米）。默认 (0,0) 表示左下角即原点。 */
//         public final double originOffsetX;
//         public final double originOffsetY;
//         /** yaw 是否为角度制（true=度，false=弧度）。默认弧度。 */
//         public final boolean yawInDegrees;
//         /** 朝向零点修正（弧度），会叠加到传入的 yaw 上，用于对齐不同机器人的朝向定义。 */
//         public final double yawOffsetRad;
//         /** 可选底图；为 null 时绘制纯色矩形。绘制时会拉伸铺满地图矩形。 */
//         public final BufferedImage background;

//         /** 常用构造：左下角为原点，yaw 用弧度、CCW 自 +X 轴起算，无底图。 */
//         public MapModel(String name, double widthMeters, double heightMeters) {
//             this(name, widthMeters, heightMeters, 0, 0, false, 0, null);
//         }

//         /** 完整构造，可自定义坐标系映射与底图。 */
//         public MapModel(String name, double widthMeters, double heightMeters,
//                         double originOffsetX, double originOffsetY,
//                         boolean yawInDegrees, double yawOffsetRad, BufferedImage background) {
//             this.name = name;
//             this.widthMeters = widthMeters;
//             this.heightMeters = heightMeters;
//             this.originOffsetX = originOffsetX;
//             this.originOffsetY = originOffsetY;
//             this.yawInDegrees = yawInDegrees;
//             this.yawOffsetRad = yawOffsetRad;
//             this.background = background;
//         }
//     }

//     /** 默认场地：长 28m × 宽 15m。 */
//     public static final MapModel DEFAULT_MAP = new MapModel("场地", 28.0, 15.0);


//     private static final class Pose {
//         final double x, y, yawRad;
//         Pose(double x, double y, double yawRad) {
//             this.x = x;
//             this.y = y;
//             this.yawRad = yawRad;
//         }
//     }

//     // ==== 可配置外观 ====
//     private int minimapWidthPx = 300;   // 小地图矩形像素宽度
//     private int marginPx = 20;          // 距窗口右下边缘的外边距

//     // ==== 数据模型（volatile：允许 MQTT 线程直接写入） ====
//     private volatile MapModel map = DEFAULT_MAP;
//     private volatile Pose pose = null;  // null 表示尚无定位数据

//     public MinimapOverlay() {
//         setOpaque(false);
//     }

//     // ==== 更换地图接口 ====

//     /** 更换地图（场地尺寸 / 坐标系映射 / 底图）。传 null 恢复默认场地。 */
//     public void setMap(MapModel map) {
//         this.map = (map == null) ? DEFAULT_MAP : map;
//         repaint();
//     }

//     public MapModel getMap() {
//         return map;
//     }

//     /** 设置小地图矩形的像素宽度。 */
//     public void setMinimapWidth(int px) {
//         this.minimapWidthPx = Math.max(80, px);
//         repaint();
//     }

//     /**
//      * 设置机器人位姿。线程安全。
//      *
//      * @param x   世界坐标 X（米）
//      * @param y   世界坐标 Y（米）
//      * @param yaw 朝向；单位（度 / 弧度）由当前 {@link MapModel#yawInDegrees} 决定
//      */
//     public void setRobotPosition(double x, double y, double yaw) {
//         MapModel m = map;
//         double yawRad = m.yawInDegrees ? Math.toRadians(yaw) : yaw;
//         yawRad += m.yawOffsetRad;
//         this.pose = new Pose(x, y, yawRad);
//         repaint();
//     }


//     public void clearRobotPosition() {
//         this.pose = null;
//         repaint();
//     }

//     @Override
//     protected void paintComponent(Graphics g) {
//         super.paintComponent(g);
//         int w = getWidth();
//         int h = getHeight();
//         if (w <= 0 || h <= 0) {
//             return;
//         }

//         MapModel m = map;
//         Pose p = pose;

   
//         int mapW = minimapWidthPx;
//         int mapH = (int) Math.round(mapW * (m.heightMeters / m.widthMeters));

    
//         int pad = 12;
//         int titleH = 22;
//         int footerH = 20;  
//         int panelW = mapW + pad * 2;
//         int panelH = mapH + pad * 2 + titleH + footerH;

//         int panelX = w - panelW - marginPx;
//         int panelY = h - panelH - marginPx;
//         int mapX = panelX + pad;
//         int mapY = panelY + pad + titleH;

//         Graphics2D g2 = (Graphics2D) g.create();
//         g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
//         g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
//                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

//         // 半透明圆角面板背景
//         // RoundRectangle2D panel = new RoundRectangle2D.Float(panelX, panelY, panelW, panelH, 16, 16);
//         // g2.setColor(PANEL_BG);
//         // g2.fill(panel);
//         // g2.setColor(BORDER);
//         // g2.setStroke(new BasicStroke(1.4f));
//         // g2.draw(panel);

//         // 标题
//         // g2.setFont(FONT_BOLD);
//         // g2.setColor(TITLE);
//         // String title = String.format("小地图 · %s  %.0f×%.0fm", m.name, m.widthMeters, m.heightMeters);
//         // g2.drawString(title, panelX + pad, panelY + pad + 12);

//         // 地图矩形（底图 or 纯色 + 网格）
//         drawMapRect(g2, m, mapX, mapY, mapW, mapH);

//         // 机器人标记
//         String coordText;
//         if (p != null) {
//             coordText = drawRobot(g2, m, p, mapX, mapY, mapW, mapH);
//         } else {
//             g2.setFont(FONT);
//             g2.setColor(TEXT_DIM);
//             String wait = "等待定位...";
//             int tw = g2.getFontMetrics().stringWidth(wait);
//             g2.drawString(wait, mapX + (mapW - tw) / 2, mapY + mapH / 2);
//             coordText = "x= --   y= --   yaw= --";
//         }

//         // 底部坐标读数
//         g2.setFont(FONT);
//         g2.setColor(TEXT);
//         g2.drawString(coordText, mapX, mapY + mapH + 15);

//         g2.dispose();
//     }

//     /** 绘制地图矩形本体：有底图则拉伸绘制，否则纯色填充并叠加 1m 网格。 */
//     private void drawMapRect(Graphics2D g2, MapModel m, int mapX, int mapY, int mapW, int mapH) {
//         if (m.background != null) {
//             g2.drawImage(m.background, mapX, mapY, mapW, mapH, this);
//         } else {
//             g2.setColor(MAP_FILL);
//             g2.fillRect(mapX, mapY, mapW, mapH);

//             // 每 1 米一条网格线
//             g2.setColor(GRID);
//             g2.setStroke(new BasicStroke(1f));
//             for (double xm = 1; xm < m.widthMeters; xm += 1) {
//                 int gx = mapX + (int) Math.round(xm / m.widthMeters * mapW);
//                 g2.drawLine(gx, mapY, gx, mapY + mapH);
//             }
//             for (double ym = 1; ym < m.heightMeters; ym += 1) {
//                 // 世界 Y 向上，屏幕 Y 向下 → 翻转
//                 int gy = mapY + mapH - (int) Math.round(ym / m.heightMeters * mapH);
//                 g2.drawLine(mapX, gy, mapX + mapW, gy);
//             }
//         }
//         // 边框
//         g2.setColor(BORDER);
//         g2.setStroke(new BasicStroke(1.6f));
//         g2.drawRect(mapX, mapY, mapW, mapH);
//     }

//     /**
//      * 绘制机器人位置与朝向，返回坐标读数文字。
//      *
//      * <p>坐标映射：世界 (x, y) 米 → 地图矩形内像素。世界 +X 向右、+Y 向上；
//      * 屏幕 Y 向下，因此纵轴翻转。yaw 以弧度、CCW 自 +X 轴起算。
//      */
//     private String drawRobot(Graphics2D g2, MapModel m, Pose p, int mapX, int mapY, int mapW, int mapH) {
//         // 相对地图左下角的世界坐标
//         double wx = p.x + m.originOffsetX;
//         double wy = p.y + m.originOffsetY;

//         // 归一化到 [0,1]（超出范围则夹取到边缘绘制，读数仍显示真实值）
//         double fx = clamp01(wx / m.widthMeters);
//         double fy = clamp01(wy / m.heightMeters);

//         double sx = mapX + fx * mapW;
//         double sy = mapY + mapH - fy * mapH;   // 纵轴翻转

//         // 朝向三角：屏幕角度 = -yaw（世界 CCW ↔ 屏幕因 Y 翻转而顺时针）
//         double screenAngle = -p.yawRad;
//         AffineTransform old = g2.getTransform();
//         g2.translate(sx, sy);
//         g2.rotate(screenAngle);

//         // 一个指向 +X（朝向）方向的等腰三角形
//         double len = 16, halfBase = 7;
//         Path2D tri = new Path2D.Double();
//         tri.moveTo(len, 0);          // 尖端（朝向）
//         tri.lineTo(-halfBase, -halfBase);
//         tri.lineTo(-halfBase, halfBase);
//         tri.closePath();
//         g2.setColor(ROBOT);
//         g2.fill(tri);
//         g2.setColor(ROBOT_EDGE);
//         g2.setStroke(new BasicStroke(1.2f));
//         g2.draw(tri);
//         g2.setTransform(old);

//         // 中心点
//         double r = 3;
//         g2.setColor(ROBOT_EDGE);
//         g2.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));

//         double yawDeg = Math.toDegrees(p.yawRad);
//         return String.format("x=%.2f  y=%.2f  yaw=%.0f°", p.x, p.y, yawDeg);
//     }

//     private static double clamp01(double v) {
//         if (v < 0) return 0;
//         if (v > 1) return 1;
//         return v;
//     }
// }


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
 * 小地图叠加层 —— 把机器人的二维坐标 (x, y) 与朝向 (yaw) 等比例标注到右下角的场地平面图上。
 *
 * <p>交互能力：
 * <ul>
 *   <li>鼠标拖动左上角手柄 —— 等比例缩放小地图（宽高比恒定为场地真实比例）</li>
 *   <li>鼠标滚轮在小地图上滚动 / 外部调用 {@link #zoomIn()} {@link #zoomOut()} —— 缩放</li>
 *   <li>单击标题栏 —— 切换全屏 / 还原</li>
 *   <li>单击地图 —— 标点（ping）</li>
 * </ul>
 *
 * <p><b>更换地图接口：</b>调用 {@link #setMap(MapModel)} 传入新的 {@link MapModel}
 * 即可切换场地尺寸 / 坐标系映射 / 底图。若日后拿到真实地图图片，只需在
 * {@code MapModel} 中携带 {@code background}，其余逻辑无需改动。
 *
 * <p>本组件铺满整窗但为透明层：通过重写 {@link #contains(int, int)}，只在小地图矩形
 * 区域内“吃”鼠标事件，其余区域的点击会穿透到下层（视频层）。
 */
public class MinimapOverlay extends JPanel {

    // ==== 配色 ====
    private static final Color PANEL_BG   = new Color(0, 0, 0, 155);
    private static final Color BORDER     = new Color(0x40c4ff);
    private static final Color MAP_FILL   = new Color(0x1b, 0x2a, 0x38, 210);
    private static final Color GRID       = new Color(64, 196, 255, 40);
    private static final Color ROBOT      = new Color(0xff5252);
    private static final Color ROBOT_EDGE = new Color(0xffffff);
    private static final Color TITLE      = new Color(0x40c4ff);
    private static final Color TEXT       = new Color(0xe0e0e0);
    private static final Color TEXT_DIM   = new Color(0x90caf9);
    // 标点（ping）配色：霓虹青，科幻 HUD 风
    private static final Color PING       = new Color(0x00e5ff);
    private static final Color PING_SOLID = new Color(0x00e5ff);
    private static final long PING_DURATION_MS = 3000;

    private static final Font FONT       = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font FONT_BOLD  = new Font(Font.MONOSPACED, Font.BOLD, 13);

    // ==== 布局常量 ====
    private static final int PAD      = 12;   // 面板内边距
    private static final int TITLE_H  = 22;   // 顶部标题条高度
    private static final int FOOTER_H = 20;   // 底部坐标读数高度
    private static final int HANDLE   = 18;   // 左上角缩放手柄尺寸
    private static final int MIN_MAP_W = 120; // 小地图最小像素宽度
    private static final int ZOOM_STEP = 28;  // 每级缩放的像素步长（与 28:15 呼应）

    /**
     * 地图模型：描述真实场地尺寸、机器人坐标系到地图的映射关系、朝向约定与（可选）底图。
     * 不可变对象；更换地图时整体替换。
     */
    public static final class MapModel {
        /** 地图名称（显示在标题上）。 */
        public final String name;
        /** 场地在 X 方向（长）的真实长度，单位米。 */
        public final double widthMeters;
        /** 场地在 Y 方向（宽）的真实长度，单位米。 */
        public final double heightMeters;
        /** 机器人坐标系原点相对「地图左下角」的偏移（米）。默认 (0,0) 表示左下角即原点。 */
        public final double originOffsetX;
        public final double originOffsetY;
        /** yaw 是否为角度制（true=度，false=弧度）。默认弧度。 */
        public final boolean yawInDegrees;
        /** 朝向零点修正（弧度），会叠加到传入的 yaw 上，用于对齐不同机器人的朝向定义。 */
        public final double yawOffsetRad;
        /** 可选底图；为 null 时绘制纯色矩形。绘制时会拉伸铺满地图矩形。 */
        public final BufferedImage background;

        /** 常用构造：左下角为原点，yaw 用弧度、CCW 自 +X 轴起算，无底图。 */
        public MapModel(String name, double widthMeters, double heightMeters) {
            this(name, widthMeters, heightMeters, 0, 0, false, 0, null);
        }

        /** 完整构造，可自定义坐标系映射与底图。 */
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

    /** 默认场地：长 28m × 宽 15m。 */
    public static final MapModel DEFAULT_MAP = new MapModel("场地", 28.0, 15.0);


    private static final class Pose {
        final double x, y, yawRad;
        Pose(double x, double y, double yawRad) {
            this.x = x;
            this.y = y;
            this.yawRad = yawRad;
        }
    }

    /** 一次标点波纹效果（世界坐标 + 发送者）。 */
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

    /** 一次布局计算结果：面板矩形、地图矩形、标题条、缩放手柄矩形。 */
    private static final class Layout {
        final Rectangle panel;
        final int mapX, mapY, mapW, mapH;
        final Rectangle titleBar;   // 顶部标题条：单击切换全屏 / 退出
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

    // ==== 可配置外观 ====
    private int minimapWidthPx = 300;   // 小地图矩形像素宽度（高度按场地宽高比自动计算）
    private int marginPx = 20;          // 距窗口右下边缘的外边距

    // ==== 状态 ====
    private volatile MapModel map = DEFAULT_MAP;
    private volatile Pose pose = null;  // null 表示尚无定位数据
    private boolean fullscreen = false;

    // 拖动缩放状态
    private boolean dragging = false;
    private int dragStartWidth;
    private java.awt.Point dragStartPoint;

    // ==== 标点（ping）状态 ====
    private BiConsumer<Double, Double> pingListener;              // 点击小地图回调世界坐标
    private final List<PingEffect> pings = new CopyOnWriteArrayList<>();
    private Timer pingAnimTimer;                                  // 波纹动画驱动

    public MinimapOverlay() {
        setOpaque(false);
        installMouseInteraction();
    }

    // ==== 更换地图接口 ====

    /** 更换地图（场地尺寸 / 坐标系映射 / 底图）。传 null 恢复默认场地。 */
    public void setMap(MapModel map) {
        this.map = (map == null) ? DEFAULT_MAP : map;
        repaint();
    }

    public MapModel getMap() {
        return map;
    }

    /** 设置小地图矩形的像素宽度（高度按场地宽高比自动计算）。 */
    public void setMinimapWidth(int px) {
        this.minimapWidthPx = clampWidth(px, getWidth(), getHeight());
        repaint();
    }


    /** 放大一级。 */
    public void zoomIn() {
        zoomBy(1);
    }

    /** 缩小一级。 */
    public void zoomOut() {
        zoomBy(-1);
    }

    /** 按级缩放（正数放大、负数缩小）。全屏时忽略。 */
    public void zoomBy(int steps) {
        if (fullscreen) {
            return;
        }
        minimapWidthPx = clampWidth(minimapWidthPx + steps * ZOOM_STEP, getWidth(), getHeight());
        repaint();
    }

    /** 切换全屏 / 还原。 */
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

    // ==== 数据写入 ====

    /**
     * 设置机器人位姿。线程安全。
     *
     * @param x   世界坐标 X（米）
     * @param y   世界坐标 Y（米）
     * @param yaw 朝向；单位（度 / 弧度）由当前 {@link MapModel#yawInDegrees} 决定
     */
    public void setRobotPosition(double x, double y, double yaw) {
        MapModel m = map;
        double yawRad = m.yawInDegrees ? Math.toRadians(yaw) : yaw;
        yawRad += m.yawOffsetRad;
        this.pose = new Pose(x, y, yawRad);
        repaint();
    }

    /**
     * 设置机器人位姿（含 robotId，兼容调用方，忽略 ID）。线程安全。
     */
    public void setRobotPosition(double x, double y, double yaw, int robotId) {
        setRobotPosition(x, y, yaw);
    }

    /** 清除机器人位置（回到「等待定位」状态）。 */
    public void clearRobotPosition() {
        this.pose = null;
        repaint();
    }

    // ==== 标点（ping）====

    /** 注册标点回调：点击小地图时以世界坐标 (x, y) 回调。 */
    public void setOnPingListener(BiConsumer<Double, Double> listener) {
        this.pingListener = listener;
    }

    /**
     * 显示一个标点波纹。线程安全（自动切到 EDT）。
     *
     * @param x      世界坐标 X（米）
     * @param y      世界坐标 Y（米）
     * @param sender 发送者客户端 ID（可为 null/空）
     */
    public void showPing(double x, double y, String sender) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> showPing(x, y, sender));
            return;
        }
        pings.add(new PingEffect(x, y, sender, System.currentTimeMillis(), PING_DURATION_MS));
        ensureAnimTimer();
    }

    /** 若动画定时器未运行则启动；无标点时自动停止。 */
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

    /** 单击标点：双击防抖（250ms 内再来一次则取消）。 */
    /** 把地图内点击坐标换算为世界坐标并回调（无防抖，快速连点也只标点）。 */
    private void pingAt(java.awt.Point p) {
        if (pingListener == null) {
            return;
        }
        Layout l = computeLayout(getWidth(), getHeight());
        MapModel m = map;
        double fx = clamp01((p.x - l.mapX) / (double) l.mapW);
        double fy = clamp01(1 - (p.y - l.mapY) / (double) l.mapH); // Y 翻转
        double wx = fx * m.widthMeters - m.originOffsetX;
        double wy = fy * m.heightMeters - m.originOffsetY;
        pingListener.accept(wx, wy);
    }

    // ==== 鼠标交互 ====

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
                // 手柄在左上角、面板锚定在右下角：向左上拖 = 变大
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
                // 地图矩形内 → 标点（任意次数，快速连点也只标点，不切屏）
                if (p.x >= l.mapX && p.x < l.mapX + l.mapW
                        && p.y >= l.mapY && p.y < l.mapY + l.mapH) {
                    pingAt(p);
                    return;
                }
                // 标题条 → 切换全屏 / 退出小地图
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
                    zoomBy(-e.getWheelRotation()); // 向上滚 = 放大
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    /** 只在小地图面板区域内“吃”鼠标事件；其余区域穿透到下层。全屏时占据整窗。 */
    @Override
    public boolean contains(int x, int y) {
        if (fullscreen) {
            return true;
        }
        Layout l = computeLayout(getWidth(), getHeight());
        return l.panel.contains(x, y);
    }

    // ==== 布局计算 ====

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

    /** 把期望像素宽度夹取到 [MIN_MAP_W, 适配窗口的上限]。 */
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

        // 半透明圆角面板背景
        RoundRectangle2D panel = new RoundRectangle2D.Float(
                L.panel.x, L.panel.y, L.panel.width, L.panel.height, 16, 16);
        g2.setColor(PANEL_BG);
        g2.fill(panel);
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.4f));
        g2.draw(panel);

        // 标题
        g2.setFont(FONT_BOLD);
        g2.setColor(TITLE);
        String title = String.format("小地图 · %s  %.0f×%.0fm%s",
                m.name, m.widthMeters, m.heightMeters,
                fullscreen ? "  [点击标题还原]" : "");
        g2.drawString(title, L.panel.x + PAD, L.panel.y + PAD + 12);

        // 地图矩形
        drawMapRect(g2, m, L.mapX, L.mapY, L.mapW, L.mapH);

        // 机器人标记
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

        // 标点波纹（绘制在机器人之上，醒目）
        drawPings(g2, m, L.mapX, L.mapY, L.mapW, L.mapH);

        // 底部坐标读数
        g2.setFont(FONT);
        g2.setColor(TEXT);
        g2.drawString(coordText, L.mapX, L.mapY + L.mapH + 15);

        // 左上角缩放手柄
        if (L.handle != null) {
            drawGrip(g2, L.handle);
        }

        g2.dispose();
    }

    /** 绘制地图矩形本体：有底图则拉伸绘制，否则纯色填充并叠加 1m 网格。 */
    private void drawMapRect(Graphics2D g2, MapModel m, int mapX, int mapY, int mapW, int mapH) {
        if (m.background != null) {
            g2.drawImage(m.background, mapX, mapY, mapW, mapH, this);
        } else {
            g2.setColor(MAP_FILL);
            g2.fillRect(mapX, mapY, mapW, mapH);

            // 每 1 米一条网格线
            g2.setColor(GRID);
            g2.setStroke(new BasicStroke(1f));
            for (double xm = 1; xm < m.widthMeters; xm += 1) {
                int gx = mapX + (int) Math.round(xm / m.widthMeters * mapW);
                g2.drawLine(gx, mapY, gx, mapY + mapH);
            }
            for (double ym = 1; ym < m.heightMeters; ym += 1) {
                // 世界 Y 向上，屏幕 Y 向下 → 翻转
                int gy = mapY + mapH - (int) Math.round(ym / m.heightMeters * mapH);
                g2.drawLine(mapX, gy, mapX + mapW, gy);
            }
        }
        // 边框
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1.6f));
        g2.drawRect(mapX, mapY, mapW, mapH);
    }

    /**
     * 绘制机器人位置与朝向，返回坐标读数文字。
     *
     * <p>坐标映射：世界 (x, y) 米 → 地图矩形内像素。世界 +X 向右、+Y 向上；
     * 屏幕 Y 向下，因此纵轴翻转。yaw 以弧度、CCW 自 +X 轴起算。朝向标记大小随地图缩放。
     *
     * <p>标记形状为<b>水滴形</b>（尖端指向前方），尖端正上方显示机器人 ID。
     */
    private String drawRobot(Graphics2D g2, MapModel m, Pose p, int mapX, int mapY, int mapW, int mapH) {
        double wx = p.x + m.originOffsetX;
        double wy = p.y + m.originOffsetY;

        double fx = clamp01(wx / m.widthMeters);
        double fy = clamp01(wy / m.heightMeters);

        double sx = mapX + fx * mapW;
        double sy = mapY + mapH - fy * mapH;   // 纵轴翻转

        // 标记大小随地图宽度自适应
        double len = Math.max(12, mapW * 0.028);
        double yawDeg = Math.toDegrees(p.yawRad);

        // ── 水滴形路径（尖端指向 +X 方向）──
        // 使用两条贝塞尔曲线：尖端→上方鼓腹→收尾→下方鼓腹→尖端
        double screenAngle = -p.yawRad;
        AffineTransform old = g2.getTransform();
        g2.translate(sx, sy);
        g2.rotate(screenAngle);

        Path2D drop = new Path2D.Double();
        drop.moveTo(len, 0);           // 尖端
        drop.curveTo(len * 0.1, len * 0.55,
                     -len * 0.6, len * 0.45,
                     -len * 0.35, 0);    // 上方曲线到收尾
        drop.curveTo(-len * 0.6, -len * 0.45,
                     len * 0.1, -len * 0.55,
                     len, 0);            // 下方曲线回到尖端
        drop.closePath();

        g2.setColor(ROBOT);
        g2.fill(drop);
        g2.setColor(ROBOT_EDGE);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(drop);

        // ── 中心亮点 ──
        double r = Math.max(2.5, len * 0.16);
        g2.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));

        g2.setTransform(old);

        return String.format("x=%.2f  y=%.2f  yaw=%.0f°",
                p.x, p.y, yawDeg);
    }

    /**
     * 绘制标点波纹：多个由内向外扩散、透明度渐减的金色圆环 + 中心亮点 + 发送者名字。
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
            // 世界 → 像素
            double wx = pe.x + m.originOffsetX;
            double wy = pe.y + m.originOffsetY;
            double fx = clamp01(wx / m.widthMeters);
            double fy = clamp01(wy / m.heightMeters);
            double sx = mapX + fx * mapW;
            double sy = mapY + mapH - fy * mapH;   // 纵轴翻转

            // 扩散圆环：3 个，相位错开，随 progress 变大变淡
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

            // 中心亮点
            double r = Math.max(3, baseR * 0.35);
            g2.setColor(PING_SOLID);
            g2.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));

            // 发送者名字
            if (pe.sender != null && !pe.sender.isEmpty()) {
                g2.setFont(FONT);
                g2.setColor(PING_SOLID);
                g2.drawString(pe.sender, (float) (sx + r + 4), (float) (sy - r - 2));
            }
        }
    }

    /** 左上角缩放手柄：角标 + 斜向条纹，提示可拖动。 */
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
