package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.SHACLValidator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validates OWL consistency checking and SHACL auditing against real Jena runs, including
 * the exact violation count and per-violation detail messages (no simplifications).
 * Trace: PLG-REQ-05, PLG-REQ-06
 */
public class SHACLValidatorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final SHACLValidator validator = new SHACLValidator();

    private static final String SHAPES_TTL = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .

            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [
                    sh:path ex:name ;
                    sh:minCount 1 ;
                    sh:message "Person must have a name" ;
                ] .
            """;

    @Test
    public void testConsistentModelPassesReasoner() {
        String data = """
                @prefix ex: <http://example.org/> .
                ex:alice a ex:Person .
                """;
        SHACLValidator.ReasonerResult result = validator.runHermitReasoner(data);
        assertTrue("Plain typed individual must be consistent", result.isConsistent());
        assertTrue(result.getMessages().isEmpty());
    }

    @Test
    public void testDisjointClassesInconsistencyIsDetected() {
        String data = """
                @prefix ex: <http://example.org/> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                ex:A a owl:Class .
                ex:B a owl:Class .
                ex:A owl:disjointWith ex:B .
                ex:thing a ex:A , ex:B .
                """;
        SHACLValidator.ReasonerResult result = validator.runHermitReasoner(data);
        assertFalse("Individual typed by two disjoint classes must be inconsistent",
                result.isConsistent());
        assertFalse("Inconsistency must carry explanatory messages", result.getMessages().isEmpty());
    }

    @Test
    public void testShaclViolationsAreCountedIndividually() throws Exception {
        File shapes = temp.newFile("shapes.ttl");
        Files.writeString(shapes.toPath(), SHAPES_TTL, StandardCharsets.UTF_8);

        // Two persons without names -> exactly two minCount violations expected
        String data = """
                @prefix ex: <http://example.org/> .
                ex:alice a ex:Person .
                ex:bob a ex:Person .
                ex:carol a ex:Person ; ex:name "Carol" .
                """;
        SHACLValidator.SHACLAuditReport report = validator.runSHACLAudit(data, shapes.getAbsolutePath());
        assertEquals("Each violating focus node must be counted", 2, report.getViolationsCount());
        assertEquals(2, report.getMessages().size());
        assertTrue("Detail must carry the sh:message text",
                report.getMessages().get(0).contains("Person must have a name"));
    }

    @Test
    public void testConformingDataReportsZeroViolations() throws Exception {
        File shapes = temp.newFile("shapes.ttl");
        Files.writeString(shapes.toPath(), SHAPES_TTL, StandardCharsets.UTF_8);

        String data = """
                @prefix ex: <http://example.org/> .
                ex:carol a ex:Person ; ex:name "Carol" .
                """;
        SHACLValidator.SHACLAuditReport report = validator.runSHACLAudit(data, shapes.getAbsolutePath());
        assertEquals(0, report.getViolationsCount());
        assertTrue(report.getMessages().isEmpty());
    }

    @Test
    public void testMissingShapesFileFailsClosed() {
        String data = """
                @prefix ex: <http://example.org/> .
                ex:alice a ex:Person .
                """;
        SHACLValidator.SHACLAuditReport report =
                validator.runSHACLAudit(data, "does/not/exist/shapes.ttl");
        assertTrue("A missing shapes file must not report a clean audit",
                report.getViolationsCount() >= 1);
        assertTrue(report.getMessages().get(0).contains("not found"));
    }

    @Test
    public void testMalformedTurtleFailsClosed() throws Exception {
        File shapes = temp.newFile("shapes.ttl");
        Files.writeString(shapes.toPath(), SHAPES_TTL, StandardCharsets.UTF_8);

        SHACLValidator.SHACLAuditReport report =
                validator.runSHACLAudit("this is not turtle @@@", shapes.getAbsolutePath());
        assertTrue("Unparseable data must surface as a violation, never as a pass",
                report.getViolationsCount() >= 1);
    }
}
