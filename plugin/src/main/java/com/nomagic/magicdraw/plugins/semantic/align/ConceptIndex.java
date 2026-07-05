package com.nomagic.magicdraw.plugins.semantic.align;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory search index over the alignment catalog (~1,400 concepts): entry list plus
 * an inverted token index. Small enough that no persistence or paging is needed; the
 * whole index rebuilds in one catalog load.
 * Trace: v3 plan section 1
 */
public final class ConceptIndex {

    private static final Pattern SPLIT = Pattern.compile("[^\\p{Alnum}]+");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private final List<ConceptEntry> entries = new ArrayList<>();
    private final Map<String, List<ConceptEntry>> tokenPostings = new HashMap<>();

    /** Lowercased tokens from an identifier or label: camelCase and separators split. */
    public static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String rough : SPLIT.split(text)) {
            if (rough.isEmpty()) {
                continue;
            }
            for (String token : CAMEL.split(rough)) {
                if (!token.isEmpty()) {
                    tokens.add(token.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    public void add(ConceptEntry entry) {
        entries.add(entry);
        for (String token : entry.tokens()) {
            tokenPostings.computeIfAbsent(token, k -> new ArrayList<>()).add(entry);
        }
    }

    public List<ConceptEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Entries sharing at least one token with the query - the ranker scores them. */
    public Set<ConceptEntry> candidates(Set<String> queryTokens) {
        Set<ConceptEntry> result = new HashSet<>();
        for (String token : queryTokens) {
            List<ConceptEntry> exact = tokenPostings.get(token);
            if (exact != null) {
                result.addAll(exact);
            }
            // Prefix expansion lets 2-4 typed characters narrow the list ("org" -> organization)
            if (token.length() >= 2) {
                for (Map.Entry<String, List<ConceptEntry>> posting : tokenPostings.entrySet()) {
                    if (posting.getKey().startsWith(token)) {
                        result.addAll(posting.getValue());
                    }
                }
            }
        }
        return result;
    }
}
