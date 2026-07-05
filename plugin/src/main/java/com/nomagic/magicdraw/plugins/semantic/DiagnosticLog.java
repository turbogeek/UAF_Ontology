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
        String override = System.getProperty(DIR_PROPERTY);
        Path dir = (override != null && !override.isBlank())
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".semantic_alignment_plugin");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Cannot create diagnostic directory: " + dir, e);
        }
        return dir;
    }

    public static Path getLogFile() {
        return getLogDirectory().resolve(FILE_NAME);
    }

    /**
     * Appends one "timestamp | CATEGORY | message" line. Never throws: a diagnostics
     * failure must not be able to take down the host application (spec 8.4).
     */
    public static synchronized void event(String category, String message) {
        String flattened = message == null ? "" : message.replaceAll("[\\r\\n]+", " \\\\n ");
        String line = LocalDateTime.now().format(TIMESTAMP) + " | " + category + " | " + flattened
                + System.lineSeparator();
        try {
            Files.writeString(getLogFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append diagnostic event: " + line, e);
        }
    }
}
