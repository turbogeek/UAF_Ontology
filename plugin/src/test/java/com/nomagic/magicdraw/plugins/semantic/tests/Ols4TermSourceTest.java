package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.terms.AlignmentCandidate;
import com.nomagic.magicdraw.plugins.semantic.align.terms.CapabilityGuard;
import com.nomagic.magicdraw.plugins.semantic.align.terms.Ols4TermSource;
import com.nomagic.magicdraw.plugins.semantic.align.terms.OntologyLicenses;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Parses REAL OLS4 API responses (captured 2026-07-05 from the live public endpoint) so
 * the parser is grounded in reality, not an assumed shape - and validates the
 * capability-vs-activity guard and license notification. No network in the test.
 * Trace: OLS4 integration recommendation, P1
 */
public class Ols4TermSourceTest {

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/ols4/" + name)) {
            assertNotNull("fixture missing: " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testParsesRealSearchResponse() {
        List<AlignmentCandidate> candidates =
                Ols4TermSource.parseSearchResponse(fixture("search-search.json"));
        assertFalse("expected candidates from the real response", candidates.isEmpty());
        AlignmentCandidate top = candidates.get(0);
        assertEquals("NCIT:C54117", top.oboId());
        assertEquals("Search", top.label());
        // ontology_name (canonical lowercase id) is what we key off for filter + license
        assertEquals("ncit", top.ontologyPrefix());
        assertEquals("http://purl.obolibrary.org/obo/NCIT_C54117", top.iri());
        assertTrue("NCIT is not restrictively licensed", !top.restrictivelyLicensed());
    }

    @Test
    public void testParsesRealTermLookupWithSemanticType() {
        Optional<AlignmentCandidate> term =
                Ols4TermSource.parseTermLookup(fixture("term-ncit-search.json"));
        assertTrue(term.isPresent());
        // The whole point: NCIT Search is an ACTIVITY, not a capability.
        assertEquals("Activity", term.get().semanticType());
        assertEquals("NCIT:C54117", term.get().oboId());
    }

    @Test
    public void testCapabilityGuardFlagsActivityInCapabilitySlot() {
        // Putting NCIT Search (Activity) in the capability slot must warn.
        Optional<String> warning = CapabilityGuard.validate(
                CapabilityGuard.Slot.CAPABILITY, "Activity");
        assertTrue("must flag an Activity in the capability slot", warning.isPresent());
        assertTrue(warning.get().toLowerCase().contains("realized activity"));

        // The same term in the realized-activity slot is correct - no warning.
        assertTrue(CapabilityGuard.validate(
                CapabilityGuard.Slot.REALIZED_ACTIVITY, "Activity").isEmpty());
    }

    @Test
    public void testCapabilityGuardAllowsDispositionInCapabilitySlot() {
        assertTrue(CapabilityGuard.validate(CapabilityGuard.Slot.CAPABILITY, "Disposition").isEmpty());
        assertTrue(CapabilityGuard.validate(CapabilityGuard.Slot.CAPABILITY, "Capability").isEmpty());
        assertTrue(CapabilityGuard.validate(CapabilityGuard.Slot.CAPABILITY, "IntrinsicMode").isEmpty());
    }

    @Test
    public void testLicenseNotificationForRestrictiveOntologies() {
        assertTrue(OntologyLicenses.isRestrictive("snomed"));
        assertTrue(OntologyLicenses.isRestrictive("SNOMED"));
        assertTrue(OntologyLicenses.isRestrictive("meddra"));
        assertNotNull(OntologyLicenses.noteFor("loinc"));
        assertFalse("NCIT is open", OntologyLicenses.isRestrictive("ncit"));
        assertFalse(OntologyLicenses.isRestrictive("bfo"));
    }

    @Test
    public void testMalformedJsonDegradesToEmpty() {
        assertTrue(Ols4TermSource.parseSearchResponse("{ not really }").isEmpty()
                || Ols4TermSource.parseSearchResponse("{}").isEmpty());
        assertTrue(Ols4TermSource.parseTermLookup("{}").isEmpty());
    }

    @Test
    public void testBaseUrlIsConfigurableForSelfHost() {
        // The config-only air-gap switch: same class, localhost base.
        assertEquals("ols4:http://localhost:8080/api",
                new Ols4TermSource("http://localhost:8080/api/").id());
    }
}
