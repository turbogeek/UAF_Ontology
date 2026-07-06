package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.UafConceptResolver;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Validates automatic stereotype -> UAF concept resolution against the REAL uaf_ontology,
 * including the foundational (gUFO) category each concept sits under. This is the core of
 * the corrected architecture: UAF-layer alignment is a lookup, not a search.
 * Trace: v3 corrected architecture
 */
public class UafConceptResolverTest {

    private static UafConceptResolver resolver;

    @BeforeClass
    public static void load() {
        File ttl = new File(new File(System.getProperty("user.dir")).getParentFile(),
                "UAF to OWL Goals/ontologies/uaf_ontology.ttl");
        assertTrue("uaf_ontology.ttl missing: " + ttl.getAbsolutePath(), ttl.exists());
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, ttl.getAbsolutePath());
        resolver = UafConceptResolver.fromModel(model);
    }

    @Test
    public void testResolvesByStereotypeName() {
        UafConceptResolver.UafConcept org = resolver.resolve("ActualOrganization", null);
        assertNotNull("ActualOrganization must resolve automatically", org);
        assertEquals("http://purl.org/uaf/ontology#ActualOrganization", org.iri());
        assertTrue("must carry the DMM comment for the Muggle tooltip",
                org.comment().toLowerCase().contains("organization"));
    }

    @Test
    public void testResolvesByXmiIdGuid() {
        // The DMM GUID stored in the ontology (ActualOrganization).
        UafConceptResolver.UafConcept org =
                resolver.resolve("unknown-name", "3438f98e-fbef-11e1-a364-456a62c2a10f");
        assertNotNull("must resolve by xmiID when the name does not match", org);
        assertEquals("http://purl.org/uaf/ontology#ActualOrganization", org.iri());
    }

    @Test
    public void testCapabilitySitsUnderGufoAspect() {
        UafConceptResolver.UafConcept cap = resolver.resolve("Capability", null);
        assertNotNull(cap);
        // Capability -> gufo:Aspect in the ontology; the automatic foundational equivalent.
        assertEquals("http://purl.org/nemo/gufo#Aspect", cap.foundationalIri());
    }

    @Test
    public void testResourceArtifactResolves() {
        UafConceptResolver.UafConcept battery = resolver.resolve("ResourceArtifact", null);
        assertNotNull("ResourceArtifact (e.g. a Battery) must resolve", battery);
        assertEquals("http://purl.org/uaf/ontology#ResourceArtifact", battery.iri());
    }

    @Test
    public void testNameMatchIsCaseInsensitive() {
        assertNotNull(resolver.resolve("actualpost", null));
        assertNotNull(resolver.resolve("ACTUALPERSON", null));
    }

    @Test
    public void testUnmappedStereotypeReturnsNull() {
        assertNull(resolver.resolve("NotAUafStereotype", null));
        assertNull(resolver.resolve(null, null));
    }

    @Test
    public void testIndexIsRich() {
        assertTrue("expected the full UAF concept set, got " + resolver.size(),
                resolver.size() > 200);
    }
}
