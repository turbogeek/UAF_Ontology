package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;

import java.util.HashMap;
import java.util.Map;

/**
 * Classifies every catalog concept by its BASIC FORMAL ONTOLOGY (BFO) upper category, computed
 * ONCE at load from the union TBox by transitive {@code rdfs:subClassOf*} from the BFO anchors.
 * This is what makes construct-kind awareness real rather than a keyword guess: the ontology's
 * own foundational grounding says whether a concept is a process or an object.
 *
 * <ul>
 *   <li><b>OCCURRENT</b> - BFO occurrent (a process / "Act of ..."); the target for a SysML
 *       {@code Activity} / {@code Action} (BEHAVIOR).</li>
 *   <li><b>OBJECT</b> - BFO independent continuant (a material object like {@code cco:Engine});
 *       the target for a {@code Block} / {@code Part} (STRUCTURE).</li>
 *   <li><b>QUALITY</b> - BFO specifically dependent continuant (a quality / disposition); the
 *       target for a {@code ValueType} / quantity (VALUE).</li>
 *   <li><b>INFO</b> - BFO generically dependent continuant (information content).</li>
 *   <li><b>UNKNOWN</b> - not grounded in BFO (uaf / bmm / kerml / qudt / prov ... concepts get
 *       no category bias, which is correct - the bias only helps where BFO grounding exists).</li>
 * </ul>
 *
 * Cameo-free; depends only on Jena. The four traversals over ~120K triples complete in well under
 * a second (measured at load and logged).
 * Trace: design/use_cases.md UC-2.8 (construct-kind matching)
 */
public final class ConceptCategoryIndex {

    /** BFO upper categories relevant to construct-kind matching. */
    public enum Category { OCCURRENT, OBJECT, QUALITY, INFO, UNKNOWN }

    private static final String BFO = "http://purl.obolibrary.org/obo/";
    private static final String OCCURRENT_IRI = BFO + "BFO_0000003"; // occurrent
    private static final String INDEP_CONT_IRI = BFO + "BFO_0000004"; // independent continuant (objects)
    private static final String SDC_IRI = BFO + "BFO_0000020";        // specifically dependent continuant (qualities)
    private static final String GDC_IRI = BFO + "BFO_0000031";        // generically dependent continuant (info)

    private final Map<String, Category> byIri;

    private ConceptCategoryIndex(Map<String, Category> byIri) {
        this.byIri = byIri;
    }

    /** Empty index: everything UNKNOWN (used when no model / no BFO is present). */
    public static ConceptCategoryIndex empty() {
        return new ConceptCategoryIndex(new HashMap<>());
    }

    /**
     * Builds the index from a TBox model. Categories are assigned most-specific-first so the
     * disjoint BFO branches never collide: quality and object are both continuants, so they are
     * tagged from their own anchors, and the broad occurrent anchor fills the rest.
     */
    public static ConceptCategoryIndex build(Model model) {
        if (model == null || model.isEmpty()) {
            return empty();
        }
        Map<String, Category> map = new HashMap<>();
        // Order matters: narrower categories first so a class already tagged OBJECT/QUALITY is
        // not overwritten by a broader anchor. (BFO's top branches are disjoint, but assigning
        // narrowest-first is robust to any stray cross-links in imported vocabularies.)
        tag(model, map, INDEP_CONT_IRI, Category.OBJECT);
        tag(model, map, SDC_IRI, Category.QUALITY);
        tag(model, map, GDC_IRI, Category.INFO);
        tag(model, map, OCCURRENT_IRI, Category.OCCURRENT);
        return new ConceptCategoryIndex(map);
    }

    private static void tag(Model model, Map<String, Category> map, String anchorIri, Category cat) {
        String q = "SELECT DISTINCT ?c WHERE { ?c <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <"
                + anchorIri + "> }";
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                if (s.get("c") != null && s.get("c").isURIResource()) {
                    map.putIfAbsent(s.getResource("c").getURI(), cat);
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed vocabulary must not sink the whole index; those concepts stay UNKNOWN.
        }
    }

    public Category categoryOf(String iri) {
        if (iri == null) {
            return Category.UNKNOWN;
        }
        return byIri.getOrDefault(iri, Category.UNKNOWN);
    }

    /** The BFO category a given construct kind should PREFER, or null for no preference. */
    public static Category preferredFor(String constructKind) {
        if (constructKind == null) {
            return null;
        }
        switch (constructKind.toUpperCase(java.util.Locale.ROOT)) {
            case ScopeContext.BEHAVIOR:
                return Category.OCCURRENT;
            case ScopeContext.STRUCTURE:
                return Category.OBJECT;
            case ScopeContext.VALUE:
                return Category.QUALITY;
            default:
                return null; // CONNECTOR and unknowns get no category bias
        }
    }

    public int size() {
        return byIri.size();
    }
}
