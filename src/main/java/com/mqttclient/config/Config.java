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
 * Runtime JSON configuration loader.
 *
 * <p>Loads configuration from {@code config.json}, read by {@link Constants} at startup and on
 * hot-reload. Editing the JSON does not require recompilation.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>System property {@code -Dmqtt.config=path}</li>
 *   <li>{@code config.json} in the working directory</li>
 *   <li>{@code /config.json} on the classpath</li>
 *   <li>Built-in defaults when none of the above is found</li>
 * </ol>
 */
public final class Config {

    /** Default config file name (working directory / classpath). */
    public static final String CONFIG_FILE = "config.json";
    /** Overrides the config file location via -Dmqtt.config=path. */
    public static final String CONFIG_PROPERTY = "mqtt.config";

    private static JsonObject root = new JsonObject();
    private static Path currentPath;

    private Config() {
    }

    /** Reloads the config file. */
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

            // Fall back to the classpath
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

    /** Last-modified time of the config file (ms); 0 when no file is present. Used for hot-reload detection. */
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

    // ==== Typed reads ====

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
