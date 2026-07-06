package com.nomagic.magicdraw.plugins.semantic.align;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves an applied UAF profile stereotype to its UAF ontology concept AUTOMATICALLY,
 * so selecting a UAF element needs no manual alignment for the UAF layer (owner: "this
 * should be automatic as you have stereotypes with UUID that we can match to the
 * profile").
 *
 * The existing source IS uaf_ontology.ttl: each class carries rdfs:label (== the profile
 * stereotype name), a uaf:xmiID (the DMM GUID), and an rdfs:subClassOf chain that reaches
 * gUFO foundational categories. We index by both label and xmiID so a live stereotype
 * matches whether Cameo exposes its name or its GUID.
 * Trace: v3 corrected architecture
 */
public final class UafConceptResolver {

    private static final String XMI_ID = "http://purl.org/uaf/ontology#xmiID";
    private static final String GUFO_NS = "http://purl.org/nemo/gufo#";

    /** One resolved alignment: the UAF concept plus the gUFO foundational category it sits under. */
    public record UafConcept(String iri, String label, String comment,
                             String foundationalIri, String foundationalLabel) {
    }

    private final Map<String, UafConcept> byLabel = new HashMap<>();
    private final Map<String, UafConcept> byXmiId = new HashMap<>();

    private UafConceptResolver() {
    }

    /** Builds the resolver from the catalog TBox (must include uaf_ontology). */
    public static UafConceptResolver fromModel(Model model) {
        UafConceptResolver resolver = new UafConceptResolver();
        if (model == null) {
            return resolver;
        }
        Property xmiId = model.createProperty(XMI_ID);
        StmtIterator classes = model.listStatements(null, RDF.type,
                model.createResource("http://www.w3.org/2002/07/owl#Class"));
        while (classes.hasNext()) {
            Resource subject = classes.next().getSubject();
            if (!subject.isURIResource() || !subject.getURI().startsWith("http://purl.org/uaf/ontology#")) {
                continue;
            }
            String label = literal(subject, RDFS.label);
            String comment = literal(subject, RDFS.comment);
            String id = literal(subject, xmiId);
            String[] foundational = findFoundational(subject, model);
            UafConcept concept = new UafConcept(subject.getURI(),
                    label == null ? subject.getLocalName() : label,
                    comment == null ? "" : comment,
                    foundational[0], foundational[1]);
            if (label != null && !label.isBlank()) {
                resolver.byLabel.put(normalize(label), concept);
            }
            if (id != null && !id.isBlank()) {
                resolver.byXmiId.put(id, concept);
            }
        }
        return resolver;
    }

    /**
     * Resolves an applied stereotype by its name (primary) or its Cameo ID/GUID
     * (secondary). Returns null when the stereotype is not a mapped UAF concept.
     */
    public UafConcept resolve(String stereotypeName, String stereotypeId) {
        if (stereotypeName != null) {
            UafConcept byName = byLabel.get(normalize(stereotypeName));
            if (byName != null) {
                return byName;
            }
        }
        if (stereotypeId != null) {
            return byXmiId.get(stereotypeId);
        }
        return null;
    }

    public int size() {
        return byLabel.size();
    }

    /** Walks rdfs:subClassOf until a gUFO category is reached; returns {iri, label} or {null,null}. */
    private static String[] findFoundational(Resource start, Model model) {
        Resource current = start;
        for (int guard = 0; guard < 32 && current != null; guard++) {
            Statement parent = current.getProperty(RDFS.subClassOf);
            if (parent == null || !parent.getObject().isURIResource()) {
                break;
            }
            Resource parentRes = parent.getObject().asResource();
            if (parentRes.getURI().startsWith(GUFO_NS)) {
                String plabel = literal(parentRes, RDFS.label);
                return new String[]{parentRes.getURI(),
                        plabel == null ? parentRes.getLocalName() : plabel};
            }
            current = parentRes;
        }
        return new String[]{null, null};
    }

    private static String literal(Resource subject, Property property) {
        StmtIterator it = subject.listProperties(property);
        while (it.hasNext()) {
            RDFNode node = it.next().getObject();
            if (node.isLiteral()) {
                return node.asLiteral().getString();
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /** All mapped concept labels (for narrowing / diagnostics). */
    public List<String> conceptLabels() {
        return new ArrayList<>(byLabel.keySet());
    }
}
