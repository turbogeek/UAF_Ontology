package com.nomagic.magicdraw.plugins.semantic.align;

/**
 * A ranked alignment candidate for the currently selected element.
 *
 * @param entry          the catalog concept
 * @param score          normalized 0..1 confidence used for display and for the
 *                       mappingConfidence tagged value
 * @param matchedVariant the query variant (phrase / sub-phrase / word) that produced the
 *                       best text match, for a "matched: ..." display hint (may be null)
 * @param context        a short context/annotation snippet (rdfs:comment, else alt labels,
 *                       else ontology id) shown inline so the user can choose (may be null)
 * @param sbvr           an SBVR sentence for this candidate, filled by the UI layer for the
 *                       displayed top-N only (may be null)
 * Trace: v3 plan section 1; design/use_cases.md UC-2.2
 */
public record ConceptSuggestion(ConceptEntry entry, double score,
        String matchedVariant, String context, String sbvr) {

    /** Backward-compatible constructor: no variant/context/sbvr attached. */
    public ConceptSuggestion(ConceptEntry entry, double score) {
        this(entry, score, null, null, null);
    }

    /** Copy with the SBVR sentence filled in (records are immutable). */
    public ConceptSuggestion withSbvr(String sbvrSentence) {
        return new ConceptSuggestion(entry, score, matchedVariant, context, sbvrSentence);
    }

    /** Copy with an adjusted score (used for the same-ontology coherence bonus). */
    public ConceptSuggestion withScore(double newScore) {
        return new ConceptSuggestion(entry, newScore, matchedVariant, context, sbvr);
    }
}
