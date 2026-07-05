package com.nomagic.magicdraw.plugins.semantic.view;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an exported ontology as SBVR Structured English - the default, Muggle-first
 * ontology view (owner requirement: ~90% of users are not ontologists; plain English
 * is the primary window into the semantics). Rendering is capped so display cost stays
 * bounded on large models; the caller shows "N of M" and offers a full-file save.
 * Trace: v3 plan section 2, PLG-REQ-04
 */
public final class SbvrRenderer {

    /** @param sentences capped list @param totalFacts total renderable facts in the model */
    public record Rendering(List<String> sentences, long totalFacts) {
    }

    private final SBVREngine engine = new SBVREngine();

    public Rendering render(Model model, int maxSentences) {
        List<String> sentences = new ArrayList<>();
        long total = 0;
        StmtIterator statements = model.listStatements();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            String sentence = toSentence(model, statement);
            if (sentence == null) {
                continue;
            }
            total++;
            if (sentences.size() < maxSentences) {
                sentences.add(sentence);
            }
        }
        sentences.sort(String::compareTo);
        return new Rendering(sentences, total);
    }

    private String toSentence(Model model, Statement statement) {
        Resource subject = statement.getSubject();
        if (!subject.isURIResource()) {
            return null;
        }
        String subjectName = displayName(model, subject);
        RDFNode object = statement.getObject();

        if (RDF.type.equals(statement.getPredicate()) && object.isURIResource()) {
            return engine.generatePlainSBVR(subjectName, object.asResource().getURI(), null, null);
        }
        if (RDFS.subClassOf.equals(statement.getPredicate()) && object.isURIResource()) {
            return engine.generatePlainSBVR(subjectName,
                    displayName(model, object.asResource()), "specializes", null);
        }
        if (RDFS.label.equals(statement.getPredicate())) {
            return null; // labels feed displayName; a "has label" sentence is noise
        }
        if (object.isURIResource()) {
            // Relation sentence: predicate local name back to prose ("hasPart" -> "has part")
            String relation = humanizeRelation(statement.getPredicate().getLocalName());
            return "Instance: " + subjectName + " " + relation + " "
                    + displayName(model, object.asResource()) + ".";
        }
        return null;
    }

    private String displayName(Model model, Resource resource) {
        Statement label = resource.getProperty(RDFS.label);
        if (label != null && label.getObject().isLiteral()) {
            return label.getObject().asLiteral().getString();
        }
        return engine.getLocalName(resource.getURI());
    }

    public static String humanizeRelation(String localName) {
        return localName
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ")
                .toLowerCase();
    }
}
