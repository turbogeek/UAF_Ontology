package com.nomagic.magicdraw.plugins.semantic.align;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decomposes a multi-word element name (plus its stereotype term) into an ORDERED, weighted
 * set of query variants so the suggestion ranker can find best-match-first candidates across
 * ontologies. For "Extreme Weather Conditions" with stereotype "Challenge" this yields, in
 * descending tier weight: the stereotype-prefixed full phrase, the full phrase, contiguous
 * sub-phrases ("Extreme Weather", "Weather Conditions"), single words ("Extreme", "Weather",
 * "Conditions", "Challenge"), and a bounded set of one-word synonym substitutions.
 *
 * The intent (owner, 2026-07-06): a Coast Guard ontology may hold the exact challenge (best
 * match, full phrase), a meteorology ontology only "Extreme Weather" (sub-phrase), and other
 * ontologies just "Weather"/"Conditions" (words) - all should surface, ranked by specificity.
 * Trace: design/use_cases.md UC-2.2
 */
public final class QueryVariants {

    /** A single ranked query string. tier is a label for diagnostics/display. */
    public record Variant(String text, double tierWeight, String tier) {
    }

    // Small curated, one-word synonym map. Substituted one word at a time (never the cross
    // product) so the variant count stays bounded. Kept intentionally tiny and general;
    // domain synonym packs can be layered on later without changing this contract.
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "weather", List.of("meteorological", "climatic"),
            "extreme", List.of("severe"),
            "conditions", List.of("condition", "environment"),
            "condition", List.of("conditions"),
            "challenge", List.of("problem", "threat"),
            "vehicle", List.of("platform"),
            "organization", List.of("organisation"));

    private static final int MAX_VARIANTS = 40;

    private QueryVariants() {
    }

    /**
     * @param seedText        the element name (or a typed search phrase)
     * @param stereotypeTerms applied stereotype names (e.g. ["Challenge"]); may be empty
     * @return ordered variants, highest tier weight first, de-duplicated
     */
    public static List<Variant> generate(String seedText, Collection<String> stereotypeTerms) {
        List<String> words = ConceptIndex.tokensOrdered(seedText);
        List<String> stWords = new ArrayList<>();
        if (stereotypeTerms != null) {
            for (String s : stereotypeTerms) {
                stWords.addAll(ConceptIndex.tokensOrdered(s));
            }
        }

        List<Variant> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int n = words.size();

        // Tier 1: stereotype term(s) + full phrase ("Challenge Extreme Weather Conditions")
        if (!stWords.isEmpty() && n > 0) {
            List<String> combo = new ArrayList<>(stWords);
            combo.addAll(words);
            add(out, seen, String.join(" ", combo), 1.0, "FULL_STEREOTYPE");
        }
        // Tier 2: the full element phrase
        if (n > 0) {
            add(out, seen, String.join(" ", words), 0.95, "FULL");
        }
        // Tier 3: contiguous sub-phrases, longest first (weight scales with length)
        for (int len = n - 1; len >= 2; len--) {
            for (int start = 0; start + len <= n; start++) {
                String sub = String.join(" ", words.subList(start, start + len));
                double w = 0.70 * ((double) len / n);
                add(out, seen, sub, w, "SUBPHRASE");
            }
        }
        // Tier 4: single words from the name, then the stereotype term(s)
        for (String w : words) {
            add(out, seen, w, 0.40, "WORD");
        }
        for (String w : stWords) {
            add(out, seen, w, 0.40, "STEREOTYPE_WORD");
        }
        // Tier 5: one-word synonym substitutions on the full phrase, then single-word synonyms
        if (n > 0) {
            for (int i = 0; i < n && out.size() < MAX_VARIANTS; i++) {
                List<String> syns = SYNONYMS.get(words.get(i));
                if (syns == null) {
                    continue;
                }
                for (String syn : syns) {
                    List<String> copy = new ArrayList<>(words);
                    copy.set(i, syn);
                    add(out, seen, String.join(" ", copy), 0.60, "SYNONYM_PHRASE");
                }
            }
            for (String w : words) {
                List<String> syns = SYNONYMS.get(w);
                if (syns == null) {
                    continue;
                }
                for (String syn : syns) {
                    add(out, seen, syn, 0.35, "SYNONYM_WORD");
                }
            }
        }
        return out.size() > MAX_VARIANTS ? new ArrayList<>(out.subList(0, MAX_VARIANTS)) : out;
    }

    private static void add(List<Variant> out, Set<String> seen, String text, double weight, String tier) {
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !seen.add(key)) {
            return;
        }
        out.add(new Variant(trimmed, weight, tier));
    }
}
