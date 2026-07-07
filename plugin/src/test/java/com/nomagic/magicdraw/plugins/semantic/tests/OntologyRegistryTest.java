package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.OntologyRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sidebar must say WHAT a source is, not dump a raw file id ("from cco" -> "Common Core
 * Ontologies (CCO)"). Verifies the classpath ontologies.properties resolves by prefix and by
 * ontology id, collapses family ids (qudt-schema -> qudt, imce-mission -> imce, uaf_ontology ->
 * uaf, prov-o -> prov), carries a "what is it" description, and never returns a blank for an
 * unknown source (title-cased fallback).
 */
public class OntologyRegistryTest {

    private final OntologyRegistry reg = OntologyRegistry.defaults();

    @Test
    public void testKnownOntologyHasFriendlyNameAndBadge() {
        assertEquals("CCO", reg.badge("cco", "cco"));
        assertTrue(reg.fullName("cco", "cco").contains("Common Core Ontologies"));
        assertFalse("CCO must have a 'what is it' description", reg.description("cco", "cco").isBlank());
    }

    @Test
    public void testFamilyIdsCollapseToHead() {
        assertEquals("QUDT", reg.badge("qudt", "qudt-schema"));
        assertEquals("QUDT", reg.badge("qudt", "qudt-units"));
        assertEquals("QUDT", reg.badge("qudt", "qudt-quantitykinds"));
        assertTrue(reg.fullName("mission", "imce-mission").toUpperCase().contains("IMCE"));
        assertTrue(reg.fullName("uaf", "uaf_ontology").contains("UAF"));
        assertEquals("PROV-O", reg.badge("prov-o", "prov-o"));
    }

    @Test
    public void testLookupPrefersPrefixThenId() {
        // Prefix known even when the file id is odd.
        assertEquals("BFO", reg.badge("bfo", "bfo-2020-import"));
    }

    @Test
    public void testUnknownSourceFallsBackTitleCasedNeverBlank() {
        String full = reg.fullName("acme", "acme_domain_ontology");
        assertFalse(full.isBlank());
        // Title-cased, separators normalized: "Acme Domain Ontology".
        assertTrue(full.startsWith("Acme"));
        assertEquals("", reg.description("acme", "acme_domain_ontology")); // no invented description
    }
}
