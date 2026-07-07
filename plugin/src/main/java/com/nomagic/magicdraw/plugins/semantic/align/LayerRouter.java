package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Maps a UAF ARCHITECTURE LAYER to the ontology namespaces that hold the right ABSTRACTION for
 * that layer, so the same element name resolves toward the layer's intent. An Operational
 * element leans logical (uaf operational namespace); a Resource element leans physical
 * (cco / sumo / qudt); a Strategic element leans motivation (bmm). This is the layer analogue of
 * {@link StereotypeRouter}: defaults ship on the classpath ({@code /layers.properties}); a
 * same-named file in the plugin directory overrides them without a rebuild.
 * Trace: design/use_cases.md UC-2.8 (UAF-layer scope)
 */
public final class LayerRouter {

    private static final Logger log = Logger.getLogger(LayerRouter.class);

    private final Map<String, Set<String>> layerPrefixes = new HashMap<>();

    public static LayerRouter load(File pluginDirectory) {
        LayerRouter router = new LayerRouter();
        try (InputStream defaults = LayerRouter.class.getResourceAsStream("/layers.properties")) {
            if (defaults != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(defaults, StandardCharsets.UTF_8));
                router.addAll(properties);
            }
        } catch (IOException e) {
            log.error("Failed to read default layers.properties", e);
        }
        if (pluginDirectory != null) {
            File override = new File(pluginDirectory, "layers.properties");
            if (override.exists()) {
                try (FileReader reader = new FileReader(override, StandardCharsets.UTF_8)) {
                    Properties properties = new Properties();
                    properties.load(reader);
                    router.addAll(properties);
                } catch (IOException e) {
                    log.error("Failed to read layers override: " + override, e);
                }
            }
        }
        return router;
    }

    void addAll(Properties properties) {
        for (String name : properties.stringPropertyNames()) {
            Set<String> prefixes = new HashSet<>();
            for (String value : properties.getProperty(name).split(",")) {
                String trimmed = value.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    prefixes.add(trimmed);
                }
            }
            layerPrefixes.put(name.trim().toUpperCase(Locale.ROOT), prefixes);
        }
    }

    /** Preferred namespace prefixes for a UAF layer key (e.g. "RESOURCE"); empty if unknown. */
    public Set<String> prefixesFor(String layerKey) {
        if (layerKey == null) {
            return Set.of();
        }
        return layerPrefixes.getOrDefault(layerKey.trim().toUpperCase(Locale.ROOT), Set.of());
    }

    public static LayerRouter fromProperties(Properties properties) {
        LayerRouter router = new LayerRouter();
        router.addAll(properties);
        return router;
    }
}
