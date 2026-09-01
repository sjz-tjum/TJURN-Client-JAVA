package com.mqttclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时 JSON 配置加载器。
 *
 * <p>从 {@code config.json} 加载配置，供 {@link Constants} 在启动时和热重载时读取。
 * 修改 JSON 后无需重新编译。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>系统属性 {@code -Dmqtt.config=路径}</li>
 *   <li>工作目录下的 {@code config.json}</li>
 *   <li>classpath 下的 {@code /config.json}</li>
 *   <li>全部找不到时使用内置默认值</li>
 * </ol>
 */
public final class Config {

    /** 默认配置文件名称（工作目录 / classpath）。 */
    public static final String CONFIG_FILE = "config.json";
    /** 可通过 -Dmqtt.config=路径 覆盖配置文件位置。 */
    public static final String CONFIG_PROPERTY = "mqtt.config";

    private static JsonObject root = new JsonObject();
    private static Path currentPath;

    private Config() {
    }

    /** 重新加载配置文件。 */
    public static synchronized void load(String fileName) {
        try {
            String prop = System.getProperty(CONFIG_PROPERTY);
            if (prop != null && !prop.isEmpty()) {
                currentPath = Path.of(prop);
                root = JsonParser.parseString(Files.readString(currentPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                System.out.println("[Config] 已加载: " + currentPath.toAbsolutePath());
                return;
            }

            File f = new File(fileName);
            if (f.exists() && f.isFile()) {
                currentPath = f.toPath();
                root = JsonParser.parseString(Files.readString(currentPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                System.out.println("[Config] 已加载: " + currentPath.toAbsolutePath());
                return;
            }

            // 回退到 classpath
            InputStream in = Config.class.getResourceAsStream("/" + fileName);
            if (in != null) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                root = JsonParser.parseString(content).getAsJsonObject();
                System.out.println("[Config] 已从 classpath 加载: /" + fileName);
                return;
            }

            currentPath = null;
            root = new JsonObject();
            System.out.println("[Config] 未找到 " + fileName + "，使用内置默认值");
        } catch (Exception e) {
            System.err.println("[Config] 加载失败，使用默认值: " + e.getMessage());
            root = new JsonObject();
        }
    }

    /** 配置文件最后修改时间（毫秒）；无文件返回 0，供热重载检测。 */
    public static synchronized long lastModified() {
        try {
            return currentPath == null ? 0L : Files.getLastModifiedTime(currentPath).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public static boolean hasFile() {
        return currentPath != null;
    }

    // ── 类型化读取 ──────────────────────────────────────────────────

    private static JsonObject section(String section) {
        if (root.has(section) && root.get(section).isJsonObject()) {
            return root.getAsJsonObject(section);
        }
        return new JsonObject();
    }

    public static String str(String section, String key, String def) {
        try {
            JsonObject s = section(section);
            if (s.has(key)) return s.get(key).getAsString();
        } catch (Exception ignore) {
        }
        return def;
    }

    public static int i(String section, String key, int def) {
        try {
            JsonObject s = section(section);
            if (s.has(key)) return s.get(key).getAsInt();
        } catch (Exception ignore) {
        }
        return def;
    }

    public static boolean b(String section, String key, boolean def) {
        try {
            JsonObject s = section(section);
            if (s.has(key)) return s.get(key).getAsBoolean();
        } catch (Exception ignore) {
        }
        return def;
    }

    public static double d(String section, String key, double def) {
        try {
            JsonObject s = section(section);
            if (s.has(key)) return s.get(key).getAsDouble();
        } catch (Exception ignore) {
        }
        return def;
    }

    public static String[] strArray(String section, String key, String[] def) {
        try {
            JsonObject s = section(section);
            if (s.has(key) && s.get(key).isJsonArray()) {
                JsonArray arr = s.getAsJsonArray(key);
                String[] out = new String[arr.size()];
                for (int k = 0; k < arr.size(); k++) {
                    out[k] = arr.get(k).getAsString();
                }
                return out;
            }
        } catch (Exception ignore) {
        }
        return def;
    }
}
