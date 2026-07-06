package com.nomagic.magicdraw.plugins.semantic.align.terms;

/**
 * A candidate ontology term returned by a TermSource (e.g. OLS4), for narrowing a
 * model element's alignment. Carries enough provenance to be persisted and audited:
 * source ontology prefix, obo_id, IRI, plus a license note when the source is
 * restrictively licensed (owner policy: notify, don't enforce).
 *
 * @param iri            absolute term IRI
 * @param label          primary label
 * @param oboId          compact id, e.g. "NCIT:C54117"
 * @param ontologyPrefix source ontology, e.g. "ncit"
 * @param description    short definition (may be empty)
 * @param type           OLS type: class | property | individual
 * @param semanticType   term's semantic type when known (e.g. "Activity"); "" until lookup
 * @param score          source ranking score (Solr), for re-ranking
 * @param licenseNote    non-null when the source ontology is restrictively licensed
 * Trace: OLS4 integration recommendation
 */
public record AlignmentCandidate(
        String iri,
        String label,
        String oboId,
        String ontologyPrefix,
        String description,
        String type,
        String semanticType,
        double score,
        String licenseNote) {

    public boolean isClass() {
        return "class".equalsIgnoreCase(type);
    }

    public boolean restrictivelyLicensed() {
        return licenseNote != null && !licenseNote.isBlank();
    }
}
