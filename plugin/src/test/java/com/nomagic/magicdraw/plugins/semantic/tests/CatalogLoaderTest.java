package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.CatalogLoader;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Loads the REAL ontology catalog and validates the concept index is populated and
 * usable - the project's "a cache must be proven non-empty" rule applied to the
 * suggestion index that the whole alignment UX depends on.
 * Trace: v3 plan section 1
 */
public class CatalogLoaderTest {

    private static File realCatalog() {
        File dir = new File(new File(System.getProperty("user.dir")).getParentFile(),
                "UAF to OWL Goals/ontologies");
        assertTrue("catalog dir missing: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    @Test
    public void testRealCatalogProducesRichIndex() {
        ConceptIndex index = CatalogLoader.load(realCatalog());
        assertTrue("expected >800 concepts, got " + index.size(), index.size() > 800);

        ConceptEntry organization = index.entries().stream()
                .filter(e -> e.iri().equals("http://www.w3.org/ns/org#Organization"))
                .findFirst().orElse(null);
        assertNotNull("org:Organization must be indexed", organization);
        assertTrue("org concepts must carry comments for tooltips/guides",
                !organization.comment().isBlank());
        // Multilingual regression: W3C ORG has @fr/@es/... labels; English must win the
        // primary slot or exact-match ranking silently degrades (live IT6 finding).
        assertEquals("Organization", organization.label());
        assertTrue("other languages become searchable aliases",
                organization.altLabels().stream().anyMatch(l -> !l.equals("Organization")));

        assertTrue("uaf:OperationalPerformer must be indexed",
                index.entries().stream().anyMatch(e ->
                        e.iri().equals("http://purl.org/uaf/ontology#OperationalPerformer")));

        // Token search reaches the flagship concepts
        assertTrue(index.candidates(ConceptIndex.tokenize("organization")).stream()
                .anyMatch(e -> e.curie().endsWith(":Organization")));
    }

    @Test
    public void testMissingCatalogIsLoudButSafe() {
        ConceptIndex index = CatalogLoader.load(new File("does/not/exist"));
        assertTrue("missing catalog must yield an empty (not null) index", index.isEmpty());
    }

    @Test
    public void testOnDemandRegulatoryOntologyBecomesAlignable() throws Exception {
        // Owner scenario: a medical-device program imports a governance/certification
        // ontology on demand; its concepts must be indexable exactly like shipped ones.
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("user-catalog");
        java.nio.file.Files.writeString(dir.resolve("device-regulatory.ttl"), """
                @prefix owl:  <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix mdreg: <http://example.org/medical-device-regulatory#> .
                mdreg:CertifiedDevice a owl:Class ;
                    rdfs:label "Certified Device" ;
                    rdfs:comment "A medical device holding current regulatory certification." .
                mdreg:RegulatoryApproval a owl:Class ;
                    rdfs:label "Regulatory Approval" ;
                    rdfs:comment "An approval issued by a competent authority (e.g. FDA, MDR notified body)." .
                mdreg:QualityManagementSystem a owl:Class ;
                    rdfs:label "Quality Management System" ;
                    rdfs:comment "ISO 13485-style QMS governing device manufacture." .
                """);
        CatalogLoader.LoadedCatalog loaded = CatalogLoader.loadAll(dir.toFile());
        assertEquals(3, loaded.index().size());
        assertTrue("imported regulatory concept must be searchable",
                loaded.index().candidates(ConceptIndex.tokenize("certified")).stream()
                        .anyMatch(e -> e.iri().endsWith("#CertifiedDevice")));
        assertTrue("TBox triples must join the query dataset", loaded.model().size() >= 9);
    }
}
