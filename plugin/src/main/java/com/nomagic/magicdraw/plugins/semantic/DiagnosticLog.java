package com.nomagic.magicdraw.plugins.semantic;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Append-only diagnostic event journal for the plugin.
 *
 * Integration tests drive the GUI through the REST test harness and cannot introspect the
 * JavaFX scene graph from the Swing component tree, so every user-visible plugin reaction
 * (selection, mapping, audit, lifecycle, error) is also recorded here as a single parseable
 * line. This file is the assertable contract of the GUI for automated verification.
 * Trace: PLG-REQ-04, PLG-REQ-05
 */
public final class DiagnosticLog {

    private static final Logger log = Logger.getLogger(DiagnosticLog.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String FILE_NAME = "semantic-plugin.log";
    private static final String DIR_PROPERTY = "semantic.plugin.logdir";

    // Selection events fire on the EDT for every containment-tree click; the file append
    // happens on this single daemon thread so the EDT never blocks on disk I/O.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "semantic-diagnostic-writer");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Path cachedDirectory;

    // Private constructor to prevent instantiation of utility class
    private DiagnosticLog() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Resolves the diagnostic directory: -Dsemantic.plugin.logdir override first, otherwise
     * a dot-directory under user.home so the location is stable across Cameo installs and
     * contains no hard-coded drive letters (spec 8.1 platform independence).
     */
    public static Path getLogDirectory() {
        Path dir = cachedDirectory;
        if (dir != null) {
            return dir;
        }
        String override = System.getProperty(DIR_PROPERTY);
        dir = (override != null && !override.isBlank())
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".semantic_alignment_plugin");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Cannot create diagnostic directory: " + dir, e);
        }
        cachedDirectory = dir;
        return dir;
    }

    public static Path getLogFile() {
        return getLogDirectory().resolve(FILE_NAME);
    }

    /**
     * Queues one "timestamp | CATEGORY | message" line. Never throws: a diagnostics
     * failure must not be able to take down the host application (spec 8.4).
     */
    public static void event(String category, String message) {
        String flattened = message == null ? "" : message.replaceAll("[\\r\\n]+", " \\\\n ");
        String line = LocalDateTime.now().format(TIMESTAMP) + " | " + category + " | " + flattened
                + System.lineSeparator();
        try {
            WRITER.execute(() -> {
                try {
                    Files.writeString(getLogFile(), line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    log.error("Failed to append diagnostic event: " + line, e);
                }
            });
        } catch (Exception rejected) {
            log.error("Diagnostic writer rejected event: " + line, rejected);
        }
    }

    /**
     * Drains pending events on plugin close so the journal is complete before Cameo exits.
     * Does NOT call System.exit or otherwise touch the host JVM lifecycle.
     */
    public static void shutdown() {
        WRITER.shutdown();
        try {
            if (!WRITER.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Diagnostic writer did not drain within 2s; remaining events dropped.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
