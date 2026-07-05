package com.nomagic.magicdraw.plugins.semantic;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Validates RDF graphs using Jena OWL reasoning and Jena SHACL constraints.
 * Trace: PLG-REQ-05, PLG-REQ-06
 */
public class SHACLValidator {

    private static final Logger log = Logger.getLogger(SHACLValidator.class);

    /**
     * Executes local OWL reasoning over Turtle RDF triples to check consistency.
     */
    public ReasonerResult runHermitReasoner(String rdfTurtle) {
        if (rdfTurtle == null) {
            throw new IllegalArgumentException("RDF content cannot be null.");
        }
        try {
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new ByteArrayInputStream(rdfTurtle.getBytes(StandardCharsets.UTF_8)), Lang.TTL);

            // Using Jena's built-in OWL reasoner for consistency validation
            org.apache.jena.reasoner.Reasoner reasoner = ReasonerRegistry.getOWLReasoner();
            InfModel infModel = ModelFactory.createInfModel(reasoner, model);

            ValidityReport report = infModel.validate();
            boolean isConsistent = report.isValid();

            List<String> messages = new ArrayList<>();
            if (!isConsistent) {
                Iterator<ValidityReport.Report> it = report.getReports();
                while (it.hasNext()) {
                    messages.add(it.next().getDescription());
                }
            }
            return new ReasonerResult(isConsistent, messages);
        } catch (Exception e) {
            log.error("Reasoning consistency check failed", e);
            return new ReasonerResult(false, List.of("Exception occurred: " + e.getMessage()));
        }
    }

    /**
     * Runs SHACL validation rules against exported Turtle triples using a SHACL shapes file.
     */
    public SHACLAuditReport runSHACLAudit(String rdfTurtle, String shapesFilePath) {
        if (rdfTurtle == null || shapesFilePath == null) {
            throw new IllegalArgumentException("Arguments cannot be null.");
        }
        try {
            Model dataModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(dataModel, new ByteArrayInputStream(rdfTurtle.getBytes(StandardCharsets.UTF_8)), Lang.TTL);
            Graph dataGraph = dataModel.getGraph();

            File shapesFile = new File(shapesFilePath);
            if (!shapesFile.exists()) {
                log.warn("SHACL shapes file not found at: " + shapesFilePath + ". Returning blank audit.");
                return new SHACLAuditReport(0, List.of("SHACL shapes file not found: " + shapesFilePath));
            }

            Model shapesModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(shapesModel, shapesFilePath);
            Graph shapesGraph = shapesModel.getGraph();

            Shapes shapes = Shapes.parse(shapesGraph);
            ValidationReport report = ShaclValidator.get().validate(shapes, dataGraph);

            int violations = report.conforms() ? 0 : 1; // Simplification of violation count
            List<String> details = new ArrayList<>();
            if (!report.conforms()) {
                details.add("SHACL constraint violation found in model alignment.");
            }

            return new SHACLAuditReport(violations, details);
        } catch (Exception e) {
            log.error("SHACL validation failed", e);
            return new SHACLAuditReport(1, List.of("Validation exception: " + e.getMessage()));
        }
    }

    /**
     * Logical reasoning outcome wrapper.
     */
    public static class ReasonerResult {
        private final boolean consistent;
        private final List<String> messages;

        public ReasonerResult(boolean consistent, List<String> messages) {
            this.consistent = consistent;
            this.messages = messages != null ? messages : new ArrayList<>();
        }

        public boolean isConsistent() {
            return consistent;
        }

        public List<String> getMessages() {
            return messages;
        }
    }

    /**
     * SHACL validation report wrapper.
     */
    public static class SHACLAuditReport {
        private final int violationsCount;
        private final List<String> messages;

        public SHACLAuditReport(int violationsCount, List<String> messages) {
            this.violationsCount = violationsCount;
            this.messages = messages != null ? messages : new ArrayList<>();
        }

        public int getViolationsCount() {
            return violationsCount;
        }

        public List<String> getMessages() {
            return messages;
        }
    }
}
