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

    // Scope-aware scoring weights (UC-2.8). Kept small relative to text/tier so a strong text
    // match still leads, but large enough to reorder same-base siblings and to let a construct
    // kind override a wrong-category exact match. Context is capped so a pile of weak owner/
    // sibling terms cannot swamp the text signal.
    private static final double CONTEXT_UNIT = 0.5;
    private static final double CONTEXT_CAP = 0.7;
    private static final double CATEGORY_BONUS = 0.35;
    private static final double CATEGORY_PENALTY = 0.30;
    private static final double LAYER_BONUS = 0.25;

    private final ConceptIndex index;
    private final StereotypeRouter router;
    private final ConceptCategoryIndex categories;
    private final LayerRouter layerRouter;

    public SuggestionRanker(ConceptIndex index, StereotypeRouter router) {
        this(index, router, ConceptCategoryIndex.empty(), LayerRouter.fromProperties(new java.util.Properties()));
    }

    /** Scope-aware constructor: adds BFO construct-kind classification and UAF-layer routing. */
    public SuggestionRanker(ConceptIndex index, StereotypeRouter router,
                            ConceptCategoryIndex categories, LayerRouter layerRouter) {
        this.index = index;
        this.router = router;
        this.categories = categories == null ? ConceptCategoryIndex.empty() : categories;
        this.layerRouter = layerRouter == null
                ? LayerRouter.fromProperties(new java.util.Properties()) : layerRouter;
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
        return searchVariants(seedText, stereotypes, narrowFromLabel, limit, ScopeContext.EMPTY);
    }

    /**
     * Scope-aware variant search (UC-2.8). Everything the 4-arg overload does, PLUS three
     * context signals layered onto each candidate's raw score:
     * <ul>
     *   <li><b>context terms</b> (owner / type / sibling) boost concepts whose label, alt-labels
     *       or comment overlap them - a part {@code engine} owned by an SUV surfaces the vehicle
     *       engine, not a pump engine;</li>
     *   <li><b>construct kind</b> biases toward the matching BFO category (BEHAVIOR->occurrent,
     *       STRUCTURE->object, VALUE->quality) and, crucially, REVOKES the exact-match-first
     *       privilege when an exact name match is the WRONG category (so an {@code Activity}
     *       named like an object still prefers the process);</li>
     *   <li><b>UAF layer</b> boosts concepts in the layer's preferred namespaces
     *       (Resource->cco/sumo/qudt, Operational->uaf, Strategic->bmm).</li>
     * </ul>
     * With {@link ScopeContext#EMPTY} and an empty category/layer index it reproduces the
     * non-scoped ranking exactly.
     */
    public List<ConceptSuggestion> searchVariants(String seedText, Collection<String> stereotypes,
                                                  String narrowFromLabel, int limit, ScopeContext scope) {
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        ScopeContext sc = scope == null ? ScopeContext.EMPTY : scope;
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
        Set<String> layerPrefixes = layerRouter.prefixesFor(sc.layerKey());
        ConceptCategoryIndex.Category preferred = ConceptCategoryIndex.preferredFor(sc.kindKey());

        // Candidate gathering: union of tokens across every variant, plus routed/pinned, plus
        // the context terms' tokens (so a purely context-driven disambiguator is still scored).
        Set<String> allTokens = new HashSet<>();
        for (QueryVariants.Variant v : variants) {
            allTokens.addAll(ConceptIndex.tokenize(v.text()));
        }
        for (ScopeContext.ContextTerm t : sc.contextTerms()) {
            allTokens.addAll(ConceptIndex.tokenize(t.text()));
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

        List<Scored> scored = new ArrayList<>();
        for (ConceptEntry entry : candidates) {
            double bestWeighted = 0.0;
            double bestRaw = 0.0;
            double bestTier = 0.0;
            String bestVariant = null;
            for (QueryVariants.Variant v : variants) {
                String nq = v.text().toLowerCase(Locale.ROOT);
                double rawText = textScore(entry, nq, ConceptIndex.tokenize(v.text()));
                double weighted = rawText * v.tierWeight();
                if (weighted > bestWeighted) {
                    bestWeighted = weighted;
                    bestRaw = rawText;
                    bestTier = v.tierWeight();
                    bestVariant = v.text();
                }
            }
            double pin = pinned.contains(entry.curie().toLowerCase(Locale.ROOT)) ? 0.35 : 0.0;
            double routed = boosted.contains(entry.prefix().toLowerCase(Locale.ROOT)) ? 0.25 : 0.0;
            double overlap = 0.2 * jaccard(nameTokens, entry.tokens());

            // --- scope-aware terms -----------------------------------------------------------
            double contextBoost = contextBoost(entry, sc);
            ConceptCategoryIndex.Category cat = categories.categoryOf(entry.iri());
            boolean categoryConflict = preferred != null
                    && cat != ConceptCategoryIndex.Category.UNKNOWN && cat != preferred;
            double categoryBias = 0.0;
            if (preferred != null && cat != ConceptCategoryIndex.Category.UNKNOWN) {
                categoryBias = (cat == preferred) ? CATEGORY_BONUS : -CATEGORY_PENALTY;
            }
            double layerBoost = layerPrefixes.contains(entry.prefix().toLowerCase(Locale.ROOT))
                    ? LAYER_BONUS : 0.0;

            double raw = bestWeighted + pin + routed + overlap + contextBoost + categoryBias + layerBoost;
            if (raw > 0.05) {
                // A genuine EXACT primary-label match on a FULL-phrase-level variant
                // (FULL / FULL+stereotype / narrowFrom, weight >= 0.90) ranks in its own
                // class ABOVE any pin/route-boosted partial in another ontology, so the
                // Coast-Guard-exact match always wins (owner requirement; the previous
                // same-ontology coherence bonus could wrongly demote it - removed).
                // BUT: a construct-kind conflict revokes the privilege, so an Activity named
                // like an object does not get pinned to the object (UC-2.8).
                boolean exact = bestRaw >= 0.999 && bestTier >= 0.90 && !categoryConflict;
                scored.add(new Scored(new ConceptSuggestion(entry, Math.min(1.0, raw / MAX_RAW),
                        bestVariant, scopedContextOf(entry, sc, cat, contextBoost, layerBoost), null), exact));
            }
        }
        scored.sort((a, b) -> {
            if (a.exact != b.exact) {
                return a.exact ? -1 : 1;
            }
            int byScore = Double.compare(b.suggestion.score(), a.suggestion.score());
            return byScore != 0 ? byScore
                    : a.suggestion.entry().curie().compareTo(b.suggestion.entry().curie());
        });
        List<ConceptSuggestion> result = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            result.add(s.suggestion);
        }
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    /** Internal: a scored suggestion plus whether it was a full-phrase exact match. */
    private record Scored(ConceptSuggestion suggestion, boolean exact) {
    }

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

    /**
     * Disambiguation boost: how strongly the surrounding-context terms (owner / type / siblings)
     * overlap this concept's own words (label + alt-labels + comment). Each term contributes
     * {@code role.weight x CONTEXT_UNIT x (matchedTokens / termTokens)}; the total is capped so a
     * long list of weak sibling terms cannot dominate the text signal. Empty context -> 0.
     */
    private static double contextBoost(ConceptEntry entry, ScopeContext scope) {
        if (scope.contextTerms().isEmpty()) {
            return 0.0;
        }
        Set<String> concept = conceptWords(entry);
        if (concept.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (ScopeContext.ContextTerm term : scope.contextTerms()) {
            Set<String> termTokens = ConceptIndex.tokenize(term.text());
            if (termTokens.isEmpty()) {
                continue;
            }
            int matched = 0;
            for (String t : termTokens) {
                if (concept.contains(t)) {
                    matched++;
                }
            }
            if (matched > 0) {
                total += term.weight() * CONTEXT_UNIT * ((double) matched / termTokens.size());
            }
        }
        return Math.min(CONTEXT_CAP, total);
    }

    /** Label + alt-label + comment tokens, used to test context-term overlap (bounded per call). */
    private static Set<String> conceptWords(ConceptEntry entry) {
        Set<String> words = new HashSet<>(entry.tokens());
        if (entry.altLabels() != null) {
            for (String alt : entry.altLabels()) {
                words.addAll(ConceptIndex.tokenize(alt));
            }
        }
        if (entry.comment() != null && !entry.comment().isBlank()) {
            words.addAll(ConceptIndex.tokenize(entry.comment()));
        }
        return words;
    }

    /**
     * Context snippet augmented with the scope rationale, so the user sees WHY a concept was
     * boosted (e.g. "Â· fits context Â· behavior Â· resource-layer"). Falls back to the plain
     * comment/alt-label snippet when no scope signal applied.
     */
    private static String scopedContextOf(ConceptEntry entry, ScopeContext scope,
                                          ConceptCategoryIndex.Category cat, double contextBoost,
                                          double layerBoost) {
        String base = contextOf(entry);
        if (scope.isEmpty()) {
            return base;
        }
        List<String> tags = new ArrayList<>();
        if (contextBoost > 0.0) {
            tags.add("fits context");
        }
        ConceptCategoryIndex.Category preferred = ConceptCategoryIndex.preferredFor(scope.kindKey());
        if (preferred != null && cat == preferred) {
            tags.add(cat.name().toLowerCase(Locale.ROOT));
        }
        if (layerBoost > 0.0 && scope.layerKey() != null) {
            tags.add(scope.layerKey().toLowerCase(Locale.ROOT) + "-layer");
        }
        if (tags.isEmpty()) {
            return base;
        }
        String rationale = String.join(" Â· ", tags);
        return base == null || base.isBlank() ? rationale : base + "  Â· " + rationale;
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
