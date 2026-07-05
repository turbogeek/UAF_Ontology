package com.nomagic.magicdraw.plugins.semantic.align;

import java.util.List;
import java.util.Set;

/**
 * One alignable ontology concept from the catalog. Immutable; built by CatalogLoader.
 *
 * @param iri        absolute concept IRI
 * @param curie      prefixed short form shown to users (e.g. "org:Organization")
 * @param label      primary human label (rdfs:label, else local name)
 * @param altLabels  skos pref/alt labels usable as search aliases
 * @param comment    rdfs:comment for tooltips/guides (may be empty)
 * @param ontologyId catalog file base name the concept came from
 * @param prefix     namespace prefix (routing key, e.g. "org")
 * @param tokens     lowercased search tokens from label + local name
 * Trace: v3 plan section 1
 */
public record ConceptEntry(
        String iri,
        String curie,
        String label,
        List<String> altLabels,
        String comment,
        String ontologyId,
        String prefix,
        Set<String> tokens) {
}
