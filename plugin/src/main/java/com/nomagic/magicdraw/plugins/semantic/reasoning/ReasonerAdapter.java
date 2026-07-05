package com.nomagic.magicdraw.plugins.semantic.reasoning;

import org.apache.jena.rdf.model.Model;

import java.util.List;

/**
 * Engine-agnostic reasoning contract. Owner decision (2026-07-05): stay
 * non-denominational about reasoners for now - VOM/OntologyForMuggles experience shows
 * engine fit for this kind of workload is an open question, so every engine
 * (Jena rules today; ELK/HermiT/Openllet as candidates) plugs in behind this interface
 * and gets benchmarked rather than committed to.
 * Selection: -Dsemantic.plugin.reasoner (see ReasonerRegistry), default "jena-rules".
 * Trace: v3 plan section 4, PLG-REQ-06
 */
public interface ReasonerAdapter {

    /** Stable identifier used for selection and reported in audit journals. */
    String id();

    /**
     * Checks logical consistency of the union of ABox and TBox.
     *
     * @param abox the exported project individuals
     * @param tbox ontology axioms (may be empty - implementations must not fail)
     */
    ConsistencyResult checkConsistency(Model abox, Model tbox);

    /**
     * @param consistent whether the union is logically consistent
     * @param messages   human-readable explanations when inconsistent (never null)
     */
    record ConsistencyResult(boolean consistent, List<String> messages) {
    }
}
