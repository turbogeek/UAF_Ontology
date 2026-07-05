package com.nomagic.magicdraw.plugins.semantic;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-starts the REST test harness (start-harness.groovy) inside Cameo's JVM when the
 * plugin initializes, removing the manual Tools > Macros > "Test Harness - Start" click.
 * This realizes the harness README's deferred "plugin auto-start" roadmap item so
 * integration tests can run zero-touch after Cameo launches.
 *
 * The Groovy runtime comes from the required AutomatonPlugin (Cameo's macro engine), so
 * the auto-started harness runs on exactly the same interpreter the manual macro path
 * uses. Disable with -Dsemantic.plugin.harness.autostart=false; the port stays governed
 * by the harness's own -Dharness.port (default 8765).
 */
public final class HarnessBootstrap {

    private static final Logger log = Logger.getLogger(HarnessBootstrap.class);
    private static final String AUTOSTART_PROPERTY = "semantic.plugin.harness.autostart";
    private static final String SCRIPT_PROPERTY = "semantic.plugin.harness";
    private static final String PORT_PROPERTY = "harness.port";
    private static final String LOGGER_FILE = "SysMLv2Logger.groovy";

    // Private constructor to prevent instantiation of utility class
    private HarnessBootstrap() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Non-blocking: Cameo startup must never wait on the harness. */
    static void startAsync(File pluginDirectory) {
        if ("false".equalsIgnoreCase(System.getProperty(AUTOSTART_PROPERTY))) {
            DiagnosticLog.event("HARNESS", "Auto-start disabled via -D" + AUTOSTART_PROPERTY);
            return;
        }
        Thread bootstrap = new Thread(() -> start(pluginDirectory), "semantic-harness-bootstrap");
        bootstrap.setDaemon(true);
        bootstrap.start();
    }

    private static void start(File pluginDirectory) {
        try {
            int port = Integer.parseInt(System.getProperty(PORT_PROPERTY, "8765"));
            if (isPortInUse(port)) {
                // A macro-started harness (or an earlier plugin init) already owns the port.
                DiagnosticLog.event("HARNESS", "Port " + port + " already serving; auto-start skipped");
                return;
            }
            File script = resolveScript(pluginDirectory);
            if (script == null) {
                DiagnosticLog.event("ERROR", "Harness auto-start: start-harness.groovy not found "
                        + "(deploy harness/ with the plugin or set -D" + SCRIPT_PROPERTY + ")");
                return;
            }
            // The script aborts into a modal dialog when its config lacks a harnessPath
            // containing SysMLv2Logger.groovy - repair the config before evaluating.
            ensureHarnessConfig(defaultConfigFile(), script.getParentFile());

            ClassLoader cl = HarnessBootstrap.class.getClassLoader();
            Class<?> shellClass = Class.forName("groovy.lang.GroovyShell", true, cl);
            Object shell = shellClass.getConstructor(ClassLoader.class).newInstance(cl);
            shellClass.getMethod("evaluate", File.class).invoke(shell, script);
            log.info("REST test harness auto-started on port " + port + " from " + script);
            DiagnosticLog.event("HARNESS", "REST test harness auto-started on port " + port + " from " + script);
        } catch (Throwable t) {
            log.error("Harness auto-start failed", t);
            DiagnosticLog.event("ERROR", "Harness auto-start failed: " + t);
        }
    }

    static boolean isPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static File resolveScript(File pluginDirectory) {
        String override = System.getProperty(SCRIPT_PROPERTY);
        if (override != null && !override.isBlank()) {
            File file = new File(override);
            if (file.exists()) {
                return file;
            }
        }
        if (pluginDirectory != null) {
            File bundled = new File(new File(pluginDirectory, "harness"), "start-harness.groovy");
            if (bundled.exists()) {
                return bundled;
            }
        }
        return null;
    }

    static File defaultConfigFile() {
        return new File(new File(System.getProperty("user.home"), ".turbogeek_test_harness"), "config.json");
    }

    /**
     * Guarantees the harness config points at a directory that actually contains
     * SysMLv2Logger.groovy and has a readable log path. Existing valid settings are
     * preserved - a modeler's hand-tuned paths must survive plugin restarts.
     *
     * @return true when the file was created or repaired
     */
    public static boolean ensureHarnessConfig(File configFile, File harnessDir) throws IOException {
        Map<String, String> config = new LinkedHashMap<>();
        if (configFile.exists()) {
            config.putAll(parseFlatJson(Files.readString(configFile.toPath(), StandardCharsets.UTF_8)));
        }
        boolean changed = false;

        String harnessPath = config.get("harnessPath");
        boolean harnessPathValid = harnessPath != null && !harnessPath.isBlank()
                && new File(harnessPath, LOGGER_FILE).exists();
        if (!harnessPathValid) {
            config.put("harnessPath", harnessDir.getAbsolutePath());
            changed = true;
        }
        String logPath = config.get("logPath");
        if (logPath == null || logPath.isBlank()) {
            Path logs = DiagnosticLog.getLogDirectory().resolve("harness-logs");
            Files.createDirectories(logs);
            config.put("logPath", logs.toString());
            changed = true;
        }
        if (!config.containsKey("projectPath")) {
            config.put("projectPath", "");
            changed = true;
        }
        if (!config.containsKey("guiLogLevel")) {
            // ERROR keeps the GUI console from stealing focus on every request (harness
            // README caveat); file logs still capture everything.
            config.put("guiLogLevel", "ERROR");
            changed = true;
        }

        if (changed) {
            File parent = configFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.writeString(configFile.toPath(), encodeFlatJson(config), StandardCharsets.UTF_8);
        }
        return changed;
    }

    // The harness itself avoids groovy.json (FastStringService SPI breaks inside Cameo),
    // and its config is a flat string map - a minimal parser keeps this dependency-free.
    private static final Pattern JSON_ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static Map<String, String> parseFlatJson(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = JSON_ENTRY.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2)
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\""));
        }
        return result;
    }

    public static String encodeFlatJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append('}').toString();
    }
}
