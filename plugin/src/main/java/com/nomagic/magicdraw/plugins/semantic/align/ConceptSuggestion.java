package com.nomagic.magicdraw.plugins.semantic.align;

/**
 * A ranked alignment candidate for the currently selected element.
 *
 * @param entry the catalog concept
 * @param score normalized 0..1 confidence used for display and for the
 *              mappingConfidence tagged value
 * Trace: v3 plan section 1
 */
public record ConceptSuggestion(ConceptEntry entry, double score) {
}
