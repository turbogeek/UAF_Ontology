package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The curated, ORDERED list of relation phrases offered when composing a compound concept's
 * differentia (owner choice: a curated editable list, not free text or raw ontology properties).
 * Loaded from {@code /relations.txt} (classpath default, one verb phrase per line); a
 * {@code relations.txt} in the deployed plugin directory APPENDS site-specific phrases so a
 * methodologist can extend the vocabulary without a rebuild. Cameo-free; used by the compose UI.
 * Trace: design/compound_concepts.md
 */
public final class RelationVocabulary {

    private static final Logger log = Logger.getLogger(RelationVocabulary.class);

    private final List<String> phrases;

    private RelationVocabulary(List<String> phrases) {
        this.phrases = phrases;
    }

    public static RelationVocabulary load(File pluginDirectory) {
        Set<String> ordered = new LinkedHashSet<>();
        try (InputStream defaults = RelationVocabulary.class.getResourceAsStream("/relations.txt")) {
            if (defaults != null) {
                readInto(new InputStreamReader(defaults, StandardCharsets.UTF_8), ordered);
            }
        } catch (IOException e) {
            log.error("Failed to read default relations.txt", e);
        }
        if (pluginDirectory != null) {
            File override = new File(pluginDirectory, "relations.txt");
            if (override.isFile()) {
                try (FileReader r = new FileReader(override, StandardCharsets.UTF_8)) {
                    readInto(r, ordered);
                } catch (IOException e) {
                    log.error("Failed to read relations override: " + override, e);
                }
            }
        }
        return new RelationVocabulary(new ArrayList<>(ordered));
    }

    public static RelationVocabulary defaults() {
        return load(null);
    }

    private static void readInto(java.io.Reader reader, Set<String> out) throws IOException {
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
        }
    }

    /** The relation phrases, in file order. */
    public List<String> phrases() {
        return phrases;
    }

    public boolean isEmpty() {
        return phrases.isEmpty();
    }

    public int size() {
        return phrases.size();
    }
}
