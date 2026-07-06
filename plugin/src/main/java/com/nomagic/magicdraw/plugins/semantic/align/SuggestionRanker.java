package com.nomagic.magicdraw.plugins.semantic.align;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores catalog concepts against the selected element so the best alignment is one
 * click away. Zero-keystroke mode ranks by the element's own name + stereotype routing;
 * typed queries reuse the same scoring with the query text instead.
 *
 * Score = text match (exact label 1.0 > alt label 0.9 > prefix 0.7 > substring 0.5 >
 * token overlap 0.35 max) + 0.25 stereotype-routed namespace + 0.2 x token Jaccard.
 * Normalized to 0..1; doubles as the stored mappingConfidence.
 * Trace: v3 plan section 1
 */
public final class SuggestionRanker {

    private static final double MAX_RAW = 1.0 + 0.35 + 0.25 + 0.2;

    private final ConceptIndex index;
    private final StereotypeRouter router;

    public SuggestionRanker(ConceptIndex index, StereotypeRouter router) {
        this.index = index;
        this.router = router;
    }

    /** Zero-keystroke suggestions for a freshly selected element. */
    public List<ConceptSuggestion> suggestForElement(String elementName, Collection<String> stereotypes, int limit) {
        return rank(elementName, elementName, stereotypes, limit);
    }

    /** Typed-search narrowing; element context still boosts the ranking. */
    public List<ConceptSuggestion> search(String query, String elementName, Collection<String> stereotypes, int limit) {
        return rank(query, elementName, stereotypes, limit);
    }

    /**
     * Best-match-first suggestions using multi-term query DECOMPOSITION. The seed text
     * (element name, or a typed search phrase) plus the stereotype term(s) are expanded by
     * {@link QueryVariants} into full-phrase / sub-phrase / word / synonym variants, each
     * carrying a tier weight; every candidate is scored against ALL variants and keeps its
     * best (tierWeight x textScore), so an exact full-phrase match categorically outranks a
     * sub-phrase which outranks a single word. A small same-ontology coherence bonus clusters
     * concepts from the ontology of the top hit. Each result carries the matched variant and
     * a context snippet for display.
     * Trace: design/use_cases.md UC-2.2
     *
     * @param seedText        element name, or the typed search phrase
     * @param stereotypes     applied stereotype names (feed both routing and the variant text)
     * @param narrowFromLabel auto-resolved UAF concept label to add as a high-tier variant (nullable)
     */
    public List<ConceptSuggestion> searchVariants(String seedText, Collection<String> stereotypes,
                                                  String narrowFromLabel, int limit) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<QueryVariants.Variant> variants = new ArrayList<>(QueryVariants.generate(seedText, stereotypes));
        if (narrowFromLabel != null && !narrowFromLabel.isBlank()) {
            variants.add(0, new QueryVariants.Variant(narrowFromLabel.trim(), 0.90, "NARROW_FROM"));
        }
        if (variants.isEmpty()) {
            return List.of();
        }

        Set<String> nameTokens = ConceptIndex.tokenize(seedText);
        Set<String> boosted = router == null ? Set.of() : router.boostedPrefixes(stereotypes);
        Set<String> pinned = router == null ? Set.of() : router.pinnedCuries(stereotypes);

        // Candidate gathering: union of tokens across every variant, plus routed/pinned.
        Set<String> allTokens = new HashSet<>();
        for (QueryVariants.Variant v : variants) {
            allTokens.addAll(ConceptIndex.tokenize(v.text()));
        }
        Set<ConceptEntry> candidates = new HashSet<>(index.candidates(allTokens));
        if (!boosted.isEmpty() || !pinned.isEmpty()) {
            for (ConceptEntry entry : index.entries()) {
                if (boosted.contains(entry.prefix().toLowerCase(Locale.ROOT))
                        || pinned.contains(entry.curie().toLowerCase(Locale.ROOT))) {
                    candidates.add(entry);
                }
            }
        }

        List<ConceptSuggestion> scored = new ArrayList<>();
        for (ConceptEntry entry : candidates) {
            double bestText = 0.0;
            String bestVariant = null;
            for (QueryVariants.Variant v : variants) {
                String nq = v.text().toLowerCase(Locale.ROOT);
                double ts = textScore(entry, nq, ConceptIndex.tokenize(v.text())) * v.tierWeight();
                if (ts > bestText) {
                    bestText = ts;
                    bestVariant = v.text();
                }
            }
            double pin = pinned.contains(entry.curie().toLowerCase(Locale.ROOT)) ? 0.35 : 0.0;
            double routed = boosted.contains(entry.prefix().toLowerCase(Locale.ROOT)) ? 0.25 : 0.0;
            double overlap = 0.2 * jaccard(nameTokens, entry.tokens());
            double raw = bestText + pin + routed + overlap;
            if (raw > 0.05) {
                scored.add(new ConceptSuggestion(entry, Math.min(1.0, raw / MAX_RAW),
                        bestVariant, contextOf(entry), null));
            }
        }
        scored.sort(BY_SCORE);

        // Same-ontology coherence: a small, capped bonus to concepts sharing the top hit's
        // ontology. Kept tiny (0.03) so it never overturns a genuine exact match elsewhere.
        if (scored.size() > 1) {
            String topOntology = scored.get(0).entry().ontologyId();
            if (topOntology != null && !topOntology.isBlank()) {
                List<ConceptSuggestion> adjusted = new ArrayList<>(scored.size());
                for (ConceptSuggestion cs : scored) {
                    if (topOntology.equals(cs.entry().ontologyId())) {
                        adjusted.add(cs.withScore(Math.min(1.0, cs.score() + 0.03)));
                    } else {
                        adjusted.add(cs);
                    }
                }
                adjusted.sort(BY_SCORE);
                scored = adjusted;
            }
        }
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    private static final java.util.Comparator<ConceptSuggestion> BY_SCORE = (a, b) -> {
        int byScore = Double.compare(b.score(), a.score());
        return byScore != 0 ? byScore : a.entry().curie().compareTo(b.entry().curie());
    };

    /** A short context/annotation snippet for display: comment, else alt labels, else ontology. */
    private static String contextOf(ConceptEntry entry) {
        if (entry.comment() != null && !entry.comment().isBlank()) {
            return entry.comment().trim();
        }
        if (entry.altLabels() != null && !entry.altLabels().isEmpty()) {
            return "aka " + String.join(", ", entry.altLabels());
        }
        return entry.ontologyId() == null ? "" : ("from " + entry.ontologyId());
    }

    private List<ConceptSuggestion> rank(String query, String elementName,
                                         Collection<String> stereotypes, int limit) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        String normQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<String> queryTokens = ConceptIndex.tokenize(query);
        Set<String> nameTokens = ConceptIndex.tokenize(elementName);
        Set<String> boosted = router == null ? Set.of() : router.boostedPrefixes(stereotypes);
        Set<String> pinned = router == null ? Set.of() : router.pinnedCuries(stereotypes);

        // Candidates come from text tokens AND from routing: an element named
        // "ResearchDivision" shares no tokens with org:Organization, yet routing must
        // still surface it - text alone would return nothing to score.
        Set<ConceptEntry> candidates = new HashSet<>(index.candidates(queryTokens));
        if (!boosted.isEmpty() || !pinned.isEmpty()) {
            for (ConceptEntry entry : index.entries()) {
                if (boosted.contains(entry.prefix().toLowerCase(Locale.ROOT))
                        || pinned.contains(entry.curie().toLowerCase(Locale.ROOT))) {
                    candidates.add(entry);
                }
            }
        }
        if (queryTokens.isEmpty() && candidates.isEmpty()) {
            candidates.addAll(index.entries());
        }

        List<ConceptSuggestion> scored = new ArrayList<>();
        for (ConceptEntry entry : candidates) {
            double text = textScore(entry, normQuery, queryTokens);
            double pin = pinned.contains(entry.curie().toLowerCase(Locale.ROOT)) ? 0.35 : 0.0;
            double routed = boosted.contains(entry.prefix().toLowerCase(Locale.ROOT)) ? 0.25 : 0.0;
            double overlap = 0.2 * jaccard(nameTokens, entry.tokens());
            double raw = text + pin + routed + overlap;
            if (raw > 0.05) {
                scored.add(new ConceptSuggestion(entry, Math.min(1.0, raw / MAX_RAW)));
            }
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : a.entry().curie().compareTo(b.entry().curie());
        });
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    private double textScore(ConceptEntry entry, String normQuery, Set<String> queryTokens) {
        if (normQuery.isEmpty()) {
            return 0.0;
        }
        String label = entry.label().toLowerCase(Locale.ROOT);
        String compactLabel = label.replace(" ", "");
        String compactQuery = normQuery.replace(" ", "");
        if (label.equals(normQuery) || compactLabel.equals(compactQuery)) {
            return 1.0;
        }
        for (String alt : entry.altLabels()) {
            String normAlt = alt.toLowerCase(Locale.ROOT);
            if (normAlt.equals(normQuery) || normAlt.replace(" ", "").equals(compactQuery)) {
                return 0.9;
            }
        }
        if (compactLabel.startsWith(compactQuery)) {
            return 0.7;
        }
        if (compactLabel.contains(compactQuery)) {
            return 0.5;
        }
        // Fuzzy: proportional token overlap between query and concept
        Set<String> shared = new HashSet<>(queryTokens);
        shared.retainAll(entry.tokens());
        if (shared.isEmpty()) {
            return 0.0;
        }
        return 0.35 * shared.size() / Math.max(queryTokens.size(), 1);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);
        if (shared.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) shared.size() / union.size();
    }
}
