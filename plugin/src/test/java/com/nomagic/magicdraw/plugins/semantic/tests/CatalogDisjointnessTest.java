package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.SHACLValidator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the disjointness axioms integrated into the catalog ontologies
 * (uaf_ontology, uafsml_ontology, sysml2, kerml). These axioms exist so the reasoner
 * can catch real modeling errors - the flagship case being ACTUAL elements placed in
 * LOGICAL operational views (e.g. a real organization typed as/alongside an
 * OperationalPerformer). Before 2026-07-05 the UAF ontologies carried no disjointness
 * at all, making every consistency check over them vacuous.
 * Trace: PLG-REQ-06, v3 plan section 4
 */
public class CatalogDisjointnessTest {

    private static final String UAF = "http://purl.org/uaf/ontology#";
    private static final String UAFSML = "http://purl.org/uaf/uafsml#";
    private static final String SYSML2 = "http://omg.org/spec/SysML2#";

    private final SHACLValidator validator = new SHACLValidator();

    private static File catalog(String name) {
        // Gradle test working dir is the plugin project dir; the catalog lives beside it.
        File file = new File(new File(System.getProperty("user.dir")).getParentFile(),
                "UAF to OWL Goals/ontologies/" + name);
        assertTrue("catalog file missing: " + file.getAbsolutePath(), file.exists());
        return file;
    }

    @Test
    public void testAllModifiedCatalogFilesStillParse() {
        for (String name : List.of("uaf_ontology.ttl", "uafsml_ontology.ttl", "sysml2.ttl", "kerml.ttl")) {
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, catalog(name).getAbsolutePath());
            assertTrue(name + " parsed but is empty", model.size() > 0);
        }
    }

    @Test
    public void testActualOrganizationInOperationalViewIsDetected() {
        // The flagship error: a real organization modeled into the logical
        // operational viewpoint. Must now be a provable inconsistency.
        String abox = """
                @prefix ex:  <http://example.org/model#> .
                @prefix uaf: <%s> .
                ex:AcmeCorp a uaf:OperationalPerformer , uaf:ActualOrganization .
                """.formatted(UAF);
        SHACLValidator.ReasonerResult result =
                validator.runHermitReasoner(abox, List.of(catalog("uaf_ontology.ttl")));
        assertFalse("actual organization typed into the operational view must be inconsistent",
                result.isConsistent());
        assertTrue("explanation must name the conflict",
                String.join(" ", result.getMessages()).contains("disjoint"));
    }

    @Test
    public void testActualPostInOperationalViewIsDetectedViaSubclassChain() {
        // ActualPost is only transitively an ActualResource (via
        // ActualOrganizationalResource); disjointness must propagate down the chain.
        String abox = """
                @prefix ex:  <http://example.org/model#> .
                @prefix uaf: <%s> .
                ex:LeadArchitect a uaf:OperationalPerformer , uaf:ActualPost .
                """.formatted(UAF);
        SHACLValidator.ReasonerResult result =
                validator.runHermitReasoner(abox, List.of(catalog("uaf_ontology.ttl")));
        assertFalse("subclass of ActualResource in the operational view must be inconsistent",
                result.isConsistent());
    }

    @Test
    public void testProperlyLayeredModelStaysConsistent() {
        // Control: logical element in the logical view, actual element separate.
        String abox = """
                @prefix ex:  <http://example.org/model#> .
                @prefix uaf: <%s> .
                ex:LogisticsRole a uaf:OperationalPerformer .
                ex:AcmeCorp a uaf:ActualOrganization .
                """.formatted(UAF);
        SHACLValidator.ReasonerResult result =
                validator.runHermitReasoner(abox, List.of(catalog("uaf_ontology.ttl")));
        assertTrue("correctly layered model must remain consistent: " + result.getMessages(),
                result.isConsistent());
    }

    @Test
    public void testCapabilityTypedAsPerformerIsDetected() {
        String abox = """
                @prefix ex:  <http://example.org/model#> .
                @prefix uaf: <%s> .
                ex:HeavyGroundAssault a uaf:Capability , uaf:ResourcePerformer .
                """.formatted(UAF);
        SHACLValidator.ReasonerResult result =
                validator.runHermitReasoner(abox, List.of(catalog("uaf_ontology.ttl")));
        assertFalse("a capability dual-typed as a performer must be inconsistent",
                result.isConsistent());
    }

    @Test
    public void testDefinitionUsageDisjointnessInSysml2AndUafsml() {
        String sysmlAbox = """
                @prefix ex:     <http://example.org/model#> .
                @prefix sysml2: <%s> .
                ex:Confused a sysml2:Definition , sysml2:Usage .
                """.formatted(SYSML2);
        assertFalse("sysml2 Definition+Usage dual typing must be inconsistent",
                validator.runHermitReasoner(sysmlAbox, List.of(catalog("sysml2.ttl"))).isConsistent());

        String uafsmlAbox = """
                @prefix ex:     <http://example.org/model#> .
                @prefix uafsml: <%s> .
                ex:Confused a uafsml:def-operationalperformer , uafsml:def-resourceperformer .
                """.formatted(UAFSML);
        assertFalse("uafsml operational+resource performer dual typing must be inconsistent",
                validator.runHermitReasoner(uafsmlAbox, List.of(catalog("uafsml_ontology.ttl"))).isConsistent());
    }
}
