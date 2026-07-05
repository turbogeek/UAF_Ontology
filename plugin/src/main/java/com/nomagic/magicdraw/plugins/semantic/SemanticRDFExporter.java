package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Association;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Generalization;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import org.apache.jena.irix.IRIx;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.log4j.Logger;

import java.io.StringWriter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traverses Cameo modeling projects and exports elements aligned with the SemanticAlignment
 * stereotype into RDF/Turtle: rdf:type assertions, rdfs:label, containment (uaf:hasPart),
 * generalizations (rdfs:subClassOf), and association-derived relationships.
 * Trace: PLG-REQ-01, PLG-REQ-02, PLG-REQ-03
 */
public class SemanticRDFExporter {

    private static final Logger log = Logger.getLogger(SemanticRDFExporter.class);

    public static final String PROJECT_NS = "http://purl.org/uaf/project/";
    public static final String UAF_NS = "http://purl.org/uaf/ontology#";
    private static final String STEREOTYPE_NAME = "SemanticAlignment";
    private static final String PROPERTY_NAME = "mappedConceptURI";
    private static final String DEFAULT_RELATION_NAME = "relatedTo";

    // Registered ontology namespaces. Doubles as the expansion table for prefixed tagged
    // values such as "sumo:MilitaryBase", so modelers are not forced to type full IRIs
    // (PLG-REQ-03 multi-ontology alignment).
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    static {
        PREFIXES.put("rdf", RDF.getURI());
        PREFIXES.put("rdfs", RDFS.getURI());
        PREFIXES.put("uaf", UAF_NS);
        PREFIXES.put("sumo", "http://www.ontologyportal.org/SUMO.owl#");
        PREFIXES.put("bmm", "http://www.omg.org/spec/BMM/");
        PREFIXES.put("org", "http://www.w3.org/ns/org#");
        PREFIXES.put("emmo", "https://w3id.org/emmo/domain/battery#");
    }
    // Alternate spellings accepted in tagged values but not emitted as Turtle prefixes.
    private static final Map<String, String> PREFIX_ALIASES = Map.of(
            "battinfo", "https://w3id.org/emmo/domain/battery#");

    private final Project project;
    private Stereotype alignmentStereotype;

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
        PREFIXES.forEach(model::setNsPrefix);

        // Resolved once per export: the stereotype lookup walks the profile tree, which is
        // too costly to repeat for every element of a large model.
        alignmentStereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        if (alignmentStereotype == null) {
            log.warn("Stereotype '" + STEREOTYPE_NAME + "' is not available in project '"
                    + project.getName() + "'; export contains no aligned individuals.");
        }

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
        // Associations rarely carry the alignment stereotype themselves: the relationship
        // triple is derived from the alignment of the two end types instead (PLG-REQ-01).
        if (element instanceof Association) {
            exportAssociation((Association) element, model);
            return;
        }

        String conceptURI = getAlignedConceptURI(element);
        if (conceptURI == null) {
            return;
        }

        Resource elementResource = model.createResource(elementURI(element));
        elementResource.addProperty(RDF.type, model.createResource(conceptURI));

        String label = sanitizeLabel(element.getHumanName());
        if (!label.isEmpty()) {
            // Quote/backslash escaping is delegated to Jena's Turtle writer; sanitizeLabel
            // removes only the control characters the writer will not neutralize (spec 8.3).
            elementResource.addProperty(RDFS.label, label);
        }

        exportContainment(element, elementResource, model);
        exportGeneralizations(element, elementResource, model);
        log.debug("Serialized RDF individual: " + element.getHumanName() + " -> " + conceptURI);
    }

    private void exportContainment(Element element, Resource elementResource, Model model) {
        Collection<Element> owned = element.getOwnedElement();
        if (owned == null) {
            return;
        }
        for (Element child : owned) {
            if (getAlignedConceptURI(child) != null) {
                elementResource.addProperty(
                        model.createProperty(UAF_NS, "hasPart"),
                        model.createResource(elementURI(child)));
            }
        }
    }

    private void exportGeneralizations(Element element, Resource elementResource, Model model) {
        if (!(element instanceof Classifier)) {
            return;
        }
        for (Generalization generalization : ((Classifier) element).getGeneralization()) {
            Classifier general = generalization.getGeneral();
            if (general != null && getAlignedConceptURI(general) != null) {
                elementResource.addProperty(RDFS.subClassOf, model.createResource(elementURI(general)));
            }
        }
    }

    private void exportAssociation(Association association, Model model) {
        List<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property> ends = association.getMemberEnd();
        if (ends == null || ends.size() < 2) {
            return;
        }
        // UML convention: memberEnd[0] is the source end, memberEnd[1] the target end.
        Type source = ends.get(0).getType();
        Type target = ends.get(1).getType();
        if (source == null || target == null
                || getAlignedConceptURI(source) == null || getAlignedConceptURI(target) == null) {
            return;
        }
        model.add(model.createResource(elementURI(source)),
                model.createProperty(UAF_NS, toPropertyLocalName(association.getName())),
                model.createResource(elementURI(target)));
    }

    private String getAlignedConceptURI(Element element) {
        if (alignmentStereotype == null || element == null
                || !StereotypesHelper.hasStereotype(element, alignmentStereotype)) {
            return null;
        }
        // Tagged values always come back as a List regardless of declared multiplicity;
        // toString() on the list itself would serialize as "[uri]" and corrupt the IRI.
        List<?> values = StereotypesHelper.getStereotypePropertyValue(
                element, alignmentStereotype, PROPERTY_NAME);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return null;
        }
        String resolved = resolveConceptURI(values.get(0).toString());
        if (resolved == null) {
            log.warn("Skipping element '" + element.getHumanName()
                    + "': mappedConceptURI is not a resolvable IRI: " + values.get(0));
        }
        return resolved;
    }

    private String elementURI(Element element) {
        return PROJECT_NS + element.getID();
    }

    /**
     * Resolves a tagged value into a safe absolute IRI, expanding registered prefixes
     * (e.g. "battinfo:BatteryPack"). Returns null when the value cannot become a legal
     * IRI - bad values are skipped rather than corrupting the Turtle document (spec 8.3).
     */
    public static String resolveConceptURI(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("://") || value.startsWith("urn:")) {
            return isValidIRI(value) ? value : null;
        }
        int colon = value.indexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            String prefix = value.substring(0, colon);
            String namespace = PREFIXES.containsKey(prefix) ? PREFIXES.get(prefix) : PREFIX_ALIASES.get(prefix);
            if (namespace != null) {
                String candidate = namespace + value.substring(colon + 1);
                return isValidIRI(candidate) ? candidate : null;
            }
        }
        return null;
    }

    static boolean isValidIRI(String candidate) {
        // IRIx tolerates some characters that still break Turtle serialization, so an
        // explicit denylist backs up the parser check (spec 8.3 injection protection).
        if (candidate.matches(".*[\\s<>\"{}|\\\\^`].*")) {
            return false;
        }
        try {
            return IRIx.create(candidate).isReference();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes control characters from a human name; Jena escapes quotes and backslashes
     * itself when writing the literal, so only the unprintables need stripping here.
     */
    public static String sanitizeLabel(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\p{Cntrl}+", " ").trim();
    }

    /**
     * Converts a human association name ("connected to") into a camelCase RDF property
     * local name ("connectedTo"); unnamed associations fall back to "relatedTo".
     */
    public static String toPropertyLocalName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return DEFAULT_RELATION_NAME;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : rawName.trim().split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            char first = sb.length() == 0
                    ? Character.toLowerCase(word.charAt(0))
                    : Character.toUpperCase(word.charAt(0));
            sb.append(first).append(word.substring(1));
        }
        if (sb.length() == 0) {
            return DEFAULT_RELATION_NAME;
        }
        if (Character.isDigit(sb.charAt(0))) {
            // NCName local parts must not start with a digit
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
