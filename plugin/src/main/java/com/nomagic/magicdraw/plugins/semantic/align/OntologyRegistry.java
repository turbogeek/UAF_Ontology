package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Human-readable metadata for the ontologies in the catalog, so the UI can say WHAT a source is
 * instead of dumping a raw file id. "from cco" becomes "Common Core Ontologies (CCO)" and a
 * hover explains it ("Mid-level ontologies extending BFO; U.S. DoD/IC-developed…").
 *
 * <p>Each entry maps a key (an ontology id or namespace prefix) to a {@code shortName | fullName |
 * description} triple, loaded from {@code /ontologies.properties} (classpath default), overridable
 * by a same-named file in the deployed plugin directory (so new ontologies get friendly names
 * without a rebuild). Lookup normalizes ids: {@code qudt-schema}/{@code qudt-units} and
 * {@code imce-mission} collapse to their family head ({@code qudt}, {@code imce}), and both the
 * concept's namespace prefix and its ontology id are tried. Unknown ids fall back to a title-cased
 * form so the UI never shows a bare token. Cameo-free; used by both the service and the plugin.</p>
 * Trace: owner request - "don't just say 'from cco', what is cco?"
 */
public final class OntologyRegistry {

    private static final Logger log = Logger.getLogger(OntologyRegistry.class);

    /** shortName = badge (e.g. "CCO"); fullName = one-line name; description = the "what is it". */
    public record Info(String shortName, String fullName, String description) {
    }

    private final Map<String, Info> byKey = new HashMap<>();

    public static OntologyRegistry load(File pluginDirectory) {
        OntologyRegistry reg = new OntologyRegistry();
        try (InputStream defaults = OntologyRegistry.class.getResourceAsStream("/ontologies.properties")) {
            if (defaults != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(defaults, StandardCharsets.UTF_8));
                reg.addAll(p);
            }
        } catch (IOException e) {
            log.error("Failed to read default ontologies.properties", e);
        }
        if (pluginDirectory != null) {
            File override = new File(pluginDirectory, "ontologies.properties");
            if (override.exists()) {
                try (FileReader r = new FileReader(override, StandardCharsets.UTF_8)) {
                    Properties p = new Properties();
                    p.load(r);
                    reg.addAll(p);
                } catch (IOException e) {
                    log.error("Failed to read ontologies override: " + override, e);
                }
            }
        }
        return reg;
    }

    public static OntologyRegistry defaults() {
        return load(null);
    }

    void addAll(Properties p) {
        for (String name : p.stringPropertyNames()) {
            String[] parts = p.getProperty(name).split("\\|", 3);
            String shortName = parts.length > 0 ? parts[0].trim() : name;
            String fullName = parts.length > 1 ? parts[1].trim() : shortName;
            String desc = parts.length > 2 ? parts[2].trim() : "";
            byKey.put(name.trim().toLowerCase(Locale.ROOT), new Info(shortName, fullName, desc));
        }
    }

    /** Best {@link Info} for a concept, trying its prefix then its ontology id (+ family heads). */
    public Info forConcept(String prefix, String ontologyId) {
        Info i = lookup(prefix);
        if (i != null) {
            return i;
        }
        i = lookup(ontologyId);
        if (i != null) {
            return i;
        }
        // Never show nothing: title-case the best token we have.
        String raw = firstNonBlank(ontologyId, prefix);
        String pretty = titleCase(raw);
        return new Info(pretty, pretty, "");
    }

    private Info lookup(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        Info i = byKey.get(id);
        if (i != null) {
            return i;
        }
        // Family head: qudt-schema -> qudt, imce-mission -> imce, uaf_ontology -> uaf, prov-o -> prov.
        String head = id.split("[-_]")[0];
        return byKey.get(head);
    }

    /** "Common Core Ontologies (CCO)" for display in a context line. */
    public String fullName(String prefix, String ontologyId) {
        return forConcept(prefix, ontologyId).fullName();
    }

    /** "CCO" for a compact badge. */
    public String badge(String prefix, String ontologyId) {
        return forConcept(prefix, ontologyId).shortName();
    }

    /** The one-line "what is it" for a tooltip (may be empty). */
    public String description(String prefix, String ontologyId) {
        return forConcept(prefix, ontologyId).description();
    }

    public int size() {
        return byKey.size();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String cleaned = s.replace('_', ' ').replace('-', ' ').trim();
        StringBuilder sb = new StringBuilder(cleaned.length());
        boolean up = true;
        for (char c : cleaned.toCharArray()) {
            if (Character.isWhitespace(c)) {
                up = true;
                sb.append(c);
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
