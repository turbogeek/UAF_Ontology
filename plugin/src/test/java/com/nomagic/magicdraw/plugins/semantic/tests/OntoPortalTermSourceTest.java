package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.terms.AlignmentCandidate;
import com.nomagic.magicdraw.plugins.semantic.align.terms.OntoPortalTermSource;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validates the OntoPortal /search response parser (one adapter covers BioPortal,
 * IndustryPortal, MatPortal, …): extracts @id→IRI, prefLabel→label, links.ontology→source
 * acronym, definition→description, and flags restrictively-licensed ontologies (SNOMEDCT).
 * Trace: design/ontology_sources.md
 */
public class OntoPortalTermSourceTest {

    // Representative OntoPortal /search response (BioPortal + IndustryPortal shapes).
    private static final String FIXTURE = "{"
            + "\"page\":1,\"pageCount\":1,\"totalCount\":2,"
            + "\"collection\":["
            + "  {"
            + "    \"@id\":\"http://purl.bioontology.org/ontology/SNOMEDCT/125605004\","
            + "    \"@type\":\"http://www.w3.org/2002/07/owl#Class\","
            + "    \"prefLabel\":\"Fracture of bone\","
            + "    \"synonym\":[\"Bone fracture\",\"Broken bone\"],"
            + "    \"definition\":[\"A traumatic breaking of a bone.\"],"
            + "    \"links\":{\"ontology\":\"https://data.bioontology.org/ontologies/SNOMEDCT\"}"
            + "  },"
            + "  {"
            + "    \"@id\":\"https://spec.industrialontologies.org/ontology/core/Core/Gearbox\","
            + "    \"@type\":\"http://www.w3.org/2002/07/owl#Class\","
            + "    \"prefLabel\":\"Gearbox\","
            + "    \"links\":{\"ontology\":\"https://industryportal.enit.fr/ontologies/IOF\"}"
            + "  }"
            + "]}";

    @Test
    public void testParsesCollectionIntoCandidates() {
        List<AlignmentCandidate> results = OntoPortalTermSource.parseSearchResponse(FIXTURE);
        assertEquals(2, results.size());
    }

    @Test
    public void testExtractsIriLabelAndSourceAcronym() {
        List<AlignmentCandidate> results = OntoPortalTermSource.parseSearchResponse(FIXTURE);
        AlignmentCandidate first = results.get(0);
        assertEquals("http://purl.bioontology.org/ontology/SNOMEDCT/125605004", first.iri());
        assertEquals("Fracture of bone", first.label());
        // links.ontology last path segment is the source acronym.
        assertEquals("SNOMEDCT", first.ontologyPrefix());
        assertEquals("SNOMEDCT:125605004", first.oboId());
        assertTrue("definition should populate description", first.description().contains("traumatic"));
        assertTrue(first.isClass());
    }

    @Test
    public void testFlagsRestrictivelyLicensedSource() {
        List<AlignmentCandidate> results = OntoPortalTermSource.parseSearchResponse(FIXTURE);
        // SNOMEDCT is license-gated - must be flagged per the notify-on-restricted policy.
        assertTrue("SNOMEDCT hit must carry a license note", results.get(0).restrictivelyLicensed());
        // IOF (IndustryPortal) is permissive - no flag.
        assertFalse("IOF hit must not be flagged", results.get(1).restrictivelyLicensed());
        assertEquals("IOF", results.get(1).ontologyPrefix());
    }

    @Test
    public void testMalformedJsonYieldsEmptyNotCrash() {
        assertTrue(OntoPortalTermSource.parseSearchResponse("not json").isEmpty());
        assertTrue(OntoPortalTermSource.parseSearchResponse("{}").isEmpty());
        assertTrue(OntoPortalTermSource.parseSearchResponse("{\"collection\":[]}").isEmpty());
    }

    @Test
    public void testUnconfiguredSourceReturnsEmptyWithoutNetwork() {
        // No API key -> search must short-circuit to empty (no network call).
        OntoPortalTermSource src = new OntoPortalTermSource("bioportal", "https://data.bioontology.org", null, null);
        assertFalse(src.isConfigured());
        assertTrue(src.search("anything", null, 5).isEmpty());
    }
}
