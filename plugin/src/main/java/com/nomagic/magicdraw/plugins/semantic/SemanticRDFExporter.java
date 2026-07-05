package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.log4j.Logger;

import java.io.StringWriter;
import java.util.Collection;

/**
 * Traverses Cameo modeling projects and exports elements aligned with custom stereotypes into RDF/Turtle format.
 * Trace: PLG-REQ-01, PLG-REQ-02, PLG-REQ-03
 */
public class SemanticRDFExporter {

    private static final Logger log = Logger.getLogger(SemanticRDFExporter.class);
    private final Project project;

    public SemanticRDFExporter(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("Project cannot be null.");
        }
        this.project = project;
    }

    /**
     * Traverses the active project element containment tree and returns a Turtle serialized string.
     */
    public String exportToTurtleString() {
        Model model = ModelFactory.createDefaultModel();
        
        // Register standard namespace prefixes for clean Turtle export
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("uaf", "http://purl.org/uaf/ontology#");
        model.setNsPrefix("sumo", "http://www.ontologyportal.org/SUMO.owl#");
        model.setNsPrefix("bmm", "http://www.omg.org/spec/BMM/");
        model.setNsPrefix("org", "http://www.w3.org/ns/org#");
        model.setNsPrefix("emmo", "https://w3id.org/emmo/domain/battery#");

        Element root = project.getPrimaryModel();
        if (root != null) {
            traverse(root, model);
        }

        StringWriter writer = new StringWriter();
        model.write(writer, "TURTLE");
        return writer.toString();
    }

    /**
     * Recursively traverses containment structure starting from target element.
     */
    private void traverse(Element element, Model model) {
        processElement(element, model);
        Collection<Element> owned = element.getOwnedElement();
        if (owned != null) {
            for (Element child : owned) {
                traverse(child, model);
            }
        }
    }

    /**
     * Analyzes individual elements, extracts stereotype alignments, and maps them to RDF triples.
     */
    private void processElement(Element element, Model model) {
        Stereotype stereotype = StereotypesHelper.getStereotype(project, "SemanticAlignment");
        if (stereotype == null || !StereotypesHelper.hasStereotype(element, stereotype)) {
            return; // Not semantically aligned, skip
        }

        // Fetch concept URI from Tagged Value
        Object tagVal = StereotypesHelper.getStereotypePropertyValue(element, stereotype, "mappedConceptURI");
        if (tagVal == null || tagVal.toString().trim().isEmpty()) {
            return;
        }

        String conceptURI = tagVal.toString().trim();
        String elementID = element.getID();
        String elementURI = "http://purl.org/uaf/project/" + elementID;

        // Build individual RDF resource and assert type relation
        Resource elementResource = model.createResource(elementURI);
        Resource conceptResource = model.createResource(conceptURI);
        elementResource.addProperty(RDF.type, conceptResource);

        log.debug("Serialized RDF Individual: " + element.getHumanName() + " -> " + conceptURI);
    }
}
