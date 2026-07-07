package com.nomagic.magicdraw.plugins.semantic.rest;

import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;

import java.io.File;
import java.nio.file.Files;

/**
 * Manages the out-of-process Semantic Catalog Service as a LOCAL child JVM: builds the
 * classpath from the deployed service jar + service-lib (Jena + log4j), launches it with a
 * bounded heap (the ontology memory lives HERE, not in Cameo), waits for /health, and stops it
 * on plugin close. The child's PID is journaled (never kill a JVM we didn't start).
 *
 * Server mode: when -Dsemantic.plugin.service.url is set, the plugin connects to that shared
 * service instead and this launcher is not used.
 * Trace: design/service_architecture.md
 */
public final class ServiceProcess {

    private static final String MAIN = "com.nomagic.magicdraw.plugins.semantic.service.SemanticCatalogService";

    private final File pluginDir;
    private final int port;
    private final String heap;
    private volatile Process process;

    public ServiceProcess(File pluginDir, int port) {
        this.pluginDir = pluginDir;
        this.port = port;
        this.heap = System.getProperty("semantic.plugin.service.heap", "1g");
    }

    public String url() {
        return "http://127.0.0.1:" + port;
    }

    /** Launches the service child JVM and blocks until it is ready (or fails). */
    public boolean start() {
        File jar = new File(pluginDir, "semantic-catalog-service.jar");
        File lib = new File(pluginDir, "service-lib");
        if (!jar.isFile() || !lib.isDirectory()) {
            DiagnosticLog.event("ERROR", "Catalog service artifacts missing (" + jar + " / " + lib
                    + "); cannot auto-start - falling back to in-process catalog.");
            return false;
        }
        String javaExe = new File(new File(System.getProperty("java.home"), "bin"),
                isWindows() ? "java.exe" : "java").getPath();
        // Wildcard classpath entry: the JVM expands "<dir>/*" itself.
        String cp = jar.getPath() + File.pathSeparator + new File(lib, "*").getPath();
        java.util.List<String> cmd = java.util.List.of(javaExe, "-Xmx" + heap, "-cp", cp, MAIN,
                "--port", String.valueOf(port), "--plugin-dir", pluginDir.getPath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            File logFile = DiagnosticLog.getLogDirectory().resolve("catalog-service.log").toFile();
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            process = pb.start();
            long pid = process.pid();
            DiagnosticLog.event("SERVICE", "Semantic Catalog Service starting pid=" + pid
                    + " port=" + port + " heap=" + heap);
            try {
                Files.writeString(DiagnosticLog.getLogDirectory().resolve("catalog-service.pid.json"),
                        "{\"pid\":" + pid + ",\"port\":" + port + "}");
            } catch (Exception ignored) {
                // pid file is best-effort
            }
            CatalogServiceClient client = new CatalogServiceClient(url());
            for (int i = 0; i < 120; i++) { // up to ~60s for a large catalog to load
                if (client.isReady()) {
                    DiagnosticLog.event("SERVICE", "Semantic Catalog Service READY on " + url()
                            + " (pid=" + pid + ")");
                    return true;
                }
                if (!process.isAlive()) {
                    DiagnosticLog.event("ERROR", "Catalog service exited during startup (pid=" + pid
                            + "); see catalog-service.log. Falling back to in-process catalog.");
                    return false;
                }
                Thread.sleep(500);
            }
            DiagnosticLog.event("ERROR", "Catalog service did not become ready in 60s (pid=" + pid + ").");
            return false;
        } catch (Exception e) {
            DiagnosticLog.event("ERROR", "Catalog service launch failed: " + e);
            return false;
        }
    }

    /** Stops the child JVM we started (graceful, then forcible). */
    public void stop() {
        Process p = process;
        if (p == null) {
            return;
        }
        try {
            long pid = p.pid();
            p.destroy();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            DiagnosticLog.event("SERVICE", "Semantic Catalog Service stopped (pid=" + pid + ")");
        } catch (Exception ignored) {
            // best-effort on shutdown
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
