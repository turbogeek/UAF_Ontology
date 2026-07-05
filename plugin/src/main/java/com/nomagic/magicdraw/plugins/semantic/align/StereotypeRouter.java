package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Routes applied stereotypes to the ontology namespaces most likely to hold the right
 * concept, so suggestions narrow before the user types anything. Rules live in
 * routes.properties (stereotype name -> comma-separated prefixes): the classpath copy
 * ships defaults; a same-named file in the plugin directory overrides them so
 * methodologists can tune routing without a rebuild.
 * Trace: v3 plan section 1
 */
public final class StereotypeRouter {

    private static final Logger log = Logger.getLogger(StereotypeRouter.class);

    // Values may mix bare prefixes ("org") and exact-concept pins ("org:Organization").
    // Pins exist because the methodology tables map stereotypes to specific concepts;
    // a namespace boost alone would tie-break alphabetically to the wrong class.
    private final Map<String, Set<String>> routes = new HashMap<>();
    private final Map<String, Set<String>> pins = new HashMap<>();

    public static StereotypeRouter load(File pluginDirectory) {
        StereotypeRouter router = new StereotypeRouter();
        try (InputStream defaults = StereotypeRouter.class.getResourceAsStream("/routes.properties")) {
            if (defaults != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(defaults, StandardCharsets.UTF_8));
                router.addAll(properties);
            }
        } catch (IOException e) {
            log.error("Failed to read default routes.properties", e);
        }
        if (pluginDirectory != null) {
            File override = new File(pluginDirectory, "routes.properties");
            if (override.exists()) {
                try (FileReader reader = new FileReader(override, StandardCharsets.UTF_8)) {
                    Properties properties = new Properties();
                    properties.load(reader);
                    router.addAll(properties);
                } catch (IOException e) {
                    log.error("Failed to read routes override: " + override, e);
                }
            }
        }
        return router;
    }

    void addAll(Properties properties) {
        for (String name : properties.stringPropertyNames()) {
            Set<String> prefixes = new HashSet<>();
            Set<String> curies = new HashSet<>();
            for (String value : properties.getProperty(name).split(",")) {
                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.contains(":")) {
                    curies.add(trimmed.toLowerCase(Locale.ROOT));
                    prefixes.add(trimmed.substring(0, trimmed.indexOf(':')).toLowerCase(Locale.ROOT));
                } else {
                    prefixes.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
            routes.put(name.toLowerCase(Locale.ROOT), prefixes);
            pins.put(name.toLowerCase(Locale.ROOT), curies);
        }
    }

    /** Exact-concept pins (lowercased CURIEs) for the applied stereotypes. */
    public Set<String> pinnedCuries(Collection<String> stereotypeNames) {
        Set<String> result = new HashSet<>();
        if (stereotypeNames != null) {
            for (String name : stereotypeNames) {
                if (name != null) {
                    result.addAll(pins.getOrDefault(name.toLowerCase(Locale.ROOT), Set.of()));
                }
            }
        }
        return result;
    }

    /** Union of boosted prefixes for every stereotype applied to the element. */
    public Set<String> boostedPrefixes(Collection<String> stereotypeNames) {
        Set<String> result = new HashSet<>();
        if (stereotypeNames != null) {
            for (String name : stereotypeNames) {
                if (name != null) {
                    result.addAll(routes.getOrDefault(name.toLowerCase(Locale.ROOT), Set.of()));
                }
            }
        }
        return result;
    }

    public static StereotypeRouter fromProperties(Properties properties) {
        StereotypeRouter router = new StereotypeRouter();
        router.addAll(properties);
        return router;
    }
}
