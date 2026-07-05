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
import org.apache.jena.shacl.validation.ReportEntry;
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
     * Without TBox axioms an ABox-only graph is trivially consistent; use the overload
     * with TBox files for a meaningful DL check.
     */
    public ReasonerResult runHermitReasoner(String rdfTurtle) {
        return runHermitReasoner(rdfTurtle, List.of());
    }

    /**
     * Executes consistency reasoning over the exported ABox unioned with the given TBox
     * ontology files, via the ACTIVE ReasonerAdapter (owner decision: engine-agnostic;
     * select with -Dsemantic.plugin.reasoner, default jena-rules). Method name is kept
     * for API stability with the design spec's "HermiT reasoning" requirement.
     * Trace: PLG-REQ-06
     */
    public ReasonerResult runHermitReasoner(String rdfTurtle, java.util.Collection<File> tboxFiles) {
        if (rdfTurtle == null) {
            throw new IllegalArgumentException("RDF content cannot be null.");
        }
        try {
            Model abox = ModelFactory.createDefaultModel();
            RDFDataMgr.read(abox, new ByteArrayInputStream(rdfTurtle.getBytes(StandardCharsets.UTF_8)), Lang.TTL);
            Model tbox = ModelFactory.createDefaultModel();
            if (tboxFiles != null) {
                for (File file : tboxFiles) {
                    if (file != null && file.exists()) {
                        RDFDataMgr.read(tbox, file.getAbsolutePath());
                    } else {
                        log.warn("TBox file missing, skipped: " + file);
                    }
                }
            }
            com.nomagic.magicdraw.plugins.semantic.reasoning.ReasonerAdapter.ConsistencyResult result =
                    com.nomagic.magicdraw.plugins.semantic.reasoning.Reasoners.active()
                            .checkConsistency(abox, tbox);
            return new ReasonerResult(result.consistent(), result.messages());
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
                log.warn("SHACL shapes file not found at: " + shapesFilePath);
                // Fail closed: a missing shapes file must never be reported as a clean audit,
                // or the dashboard badge would show green without any validation having run.
                return new SHACLAuditReport(1, List.of("SHACL shapes file not found: " + shapesFilePath));
            }

            Model shapesModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(shapesModel, shapesFilePath);
            Graph shapesGraph = shapesModel.getGraph();

            Shapes shapes = Shapes.parse(shapesGraph);
            ValidationReport report = ShaclValidator.get().validate(shapes, dataGraph);

            int violations = 0;
            List<String> details = new ArrayList<>();
            if (!report.conforms()) {
                for (ReportEntry entry : report.getEntries()) {
                    violations++;
                    String message = entry.message();
                    details.add("Violation at " + entry.focusNode() + ": "
                            + (message != null && !message.isBlank() ? message : String.valueOf(entry.constraint())));
                }
                if (violations == 0) {
                    // Defensive: a non-conforming report should always carry entries, but the
                    // dashboard must never show green on a failed validation.
                    violations = 1;
                    details.add("SHACL validation failed without detailed report entries.");
                }
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
