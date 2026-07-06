package com.nomagic.magicdraw.plugins.semantic.align.terms;

import java.util.List;
import java.util.Optional;

/**
 * A remote or local ontology-term lookup source for on-demand alignment narrowing. The
 * seam that lets the plugin point at the OLS4 public API (connected) or a self-hosted
 * OLS4 (air-gapped) with a config-only change, or fall back to bundled TTL fragments.
 * Trace: OLS4 integration recommendation
 */
public interface TermSource {

    /** Human id for diagnostics (e.g. "ols4:https://www.ebi.ac.uk/ols4/api"). */
    String id();

    /**
     * Free-text search for candidate terms.
     *
     * @param query          free text (e.g. "Search")
     * @param ontologyFilter comma-separated ontology prefixes to scope by domain, or null
     * @param limit          max candidates
     */
    List<AlignmentCandidate> search(String query, String ontologyFilter, int limit);

    /** Full record for a chosen IRI, including its semantic type (for the capability guard). */
    Optional<AlignmentCandidate> lookup(String iri);
}
