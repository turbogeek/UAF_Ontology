package com.nomagic.magicdraw.plugins.semantic.align;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;

import java.util.ArrayList;
import java.util.List;

/**
 * A COMPOUND concept: what an element means when no single ontology concept captures it, built as
 * a genus ("is a kind of") plus differentia clauses, each a (relation phrase, qualifier concept)
 * pair - e.g. genus {@code Drone} + {@code [(suppresses, Mosquito), (uses, ChemicalSprayer)]}.
 *
 * <p>It rides on the EXISTING multi-valued {@code mappedConceptURI} tag so no profile change is
 * needed: index 0 is the genus (a bare IRI, the pinned base), and each later value is either a
 * bare IRI (an additional type) or {@code "<relation phrase> | <IRI>"} (a differentia clause).
 * Bare IRIs stay backward-compatible with existing flat alignments. {@link #toSbvr} renders the
 * one sentence that reads correctly ("A X is a Drone that suppresses a Mosquito and uses a
 * Chemical Sprayer"); {@link #toStoredList} serializes back to the tag values.</p>
 * Trace: owner request - compound concepts that read correctly in SBVR; design/use_cases.md UC-2.7
 */
public final class CompoundConcept {

    /** " | " separates a differentia's relation phrase from its concept IRI in a stored value. */
    public static final String SEP = " | ";

    /**
     * A differentia: a relation phrase applied to a qualifier concept, plus an optional display
     * label so a concept whose IRI is opaque (e.g. an OBO id like {@code IDOMAL_0000746}) still
     * reads as its name ("Mosquito") in SBVR. Blank relation = a plain type.
     */
    public record Clause(String relation, String conceptIri, String label) {
        /** Convenience: a clause without a stored label (SBVR falls back to the IRI local name). */
        public Clause(String relation, String conceptIri) {
            this(relation, conceptIri, null);
        }
    }

    private final String label;          // the new concept's name (usually the element name)
    private final String genusIri;       // index-0 concept: the "is a kind of" base (may be null)
    private final List<Clause> differentia;

    public CompoundConcept(String label, String genusIri, List<Clause> differentia) {
        this.label = label;
        this.genusIri = genusIri;
        this.differentia = differentia == null ? List.of() : List.copyOf(differentia);
    }

    /**
     * Parses the stored concept values into a compound concept. The first value is the genus; each
     * subsequent value is decoded as {@code relation | iri} or, if it has no separator, a bare type.
     */
    public static CompoundConcept parse(String label, List<String> storedConcepts) {
        if (storedConcepts == null || storedConcepts.isEmpty()) {
            return new CompoundConcept(label, null, List.of());
        }
        String genus = decode(storedConcepts.get(0)).conceptIri();
        List<Clause> diff = new ArrayList<>();
        for (int i = 1; i < storedConcepts.size(); i++) {
            diff.add(decode(storedConcepts.get(i)));
        }
        return new CompoundConcept(label, genus, diff);
    }

    /**
     * Decodes one stored value into a clause: {@code "relation | iri | label"} (3 parts),
     * {@code "relation | iri"} (2), or a bare {@code "iri"} (1). IRIs never contain " | ", so the
     * split is unambiguous.
     */
    public static Clause decode(String value) {
        if (value == null) {
            return new Clause(null, null, null);
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(SEP), 3);
        if (parts.length == 1) {
            return new Clause(null, parts[0].trim(), null);
        }
        if (parts.length == 2) {
            return new Clause(parts[0].trim(), parts[1].trim(), null);
        }
        return new Clause(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    /** Encodes a differentia clause into a stored value (drops empty trailing fields). */
    public static String encode(String relation, String conceptIri, String label) {
        boolean hasRel = relation != null && !relation.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (!hasRel && !hasLabel) {
            return conceptIri;
        }
        String rel = hasRel ? relation.trim() : "";
        return hasLabel ? rel + SEP + conceptIri + SEP + label.trim() : rel + SEP + conceptIri;
    }

    /** Encodes a differentia clause without a label ("relation | iri", or bare iri if no rel). */
    public static String encode(String relation, String conceptIri) {
        return encode(relation, conceptIri, null);
    }

    /** The stored-list form: genus first, then each differentia encoded. */
    public List<String> toStoredList() {
        List<String> out = new ArrayList<>();
        if (genusIri != null) {
            out.add(genusIri);
        }
        for (Clause c : differentia) {
            out.add(encode(c.relation(), c.conceptIri(), c.label()));
        }
        return out;
    }

    /** True when at least one differentia carries a relation phrase (a real compound, not a flat list). */
    public boolean isCompound() {
        for (Clause c : differentia) {
            if (c.relation() != null && !c.relation().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Renders the SBVR Structured English sentence via the engine (uses labels when present). */
    public String toSbvr(SBVREngine engine) {
        List<String[]> clauses = new ArrayList<>();
        for (Clause c : differentia) {
            // Prefer the stored display label so an opaque IRI still reads as its name.
            String display = c.label() != null && !c.label().isBlank() ? c.label() : c.conceptIri();
            clauses.add(new String[] {c.relation(), display});
        }
        return engine.generateCompoundSBVR(label, genusIri, clauses);
    }

    public String label() {
        return label;
    }

    public String genusIri() {
        return genusIri;
    }

    public List<Clause> differentia() {
        return differentia;
    }
}
