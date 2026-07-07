package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import com.nomagic.magicdraw.plugins.semantic.align.CatalogLoader;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * Validates that the permissive breadth bundle (CCO/BFO/QUDT/PROV/ODRL/DPV) fetched by the
 * Gradle 'fetchCatalogBundle' task into build/catalog-bundle indexes correctly - in particular
 * that the generalized CatalogLoader indexes QUDT quantity-kinds / units (qudt:QuantityKind /
 * qudt:Unit INSTANCES), not just owl:Class. Skipped when the bundle has not been fetched.
 * Trace: design/ontology_sources.md
 */
public class CatalogBundleTest {

    private ConceptIndex loadBundle() {
        File dir = new File("build/catalog-bundle");
        Assume.assumeTrue("bundle not fetched (run: gradle fetchCatalogBundle) - skipping",
                dir.isDirectory() && dir.listFiles((d, n) -> n.endsWith(".ttl")) != null
                        && dir.listFiles((d, n) -> n.endsWith(".ttl")).length > 0);
        return CatalogLoader.load(dir);
    }

    private static boolean anyIriContains(ConceptIndex index, String needle) {
        for (ConceptEntry e : index.entries()) {
            if (e.iri() != null && e.iri().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyLabelEquals(ConceptIndex index, String needle) {
        for (ConceptEntry e : index.entries()) {
            if (needle.equalsIgnoreCase(e.label())) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testBundleIndexesThousandsOfConcepts() {
        ConceptIndex index = loadBundle();
        assertTrue("expected a large index from the breadth bundle, got " + index.size(),
                index.size() > 3000);
    }

    @Test
    public void testCcoClassesIndexed() {
        ConceptIndex index = loadBundle();
        assertTrue("CCO concepts must index (owl:Class)",
                anyIriContains(index, "commoncoreontologies.org"));
    }

    @Test
    public void testQudtQuantityKindsAndUnitsIndexed() {
        ConceptIndex index = loadBundle();
        // The whole point of the loader generalization: QUDT terms are qudt:QuantityKind /
        // qudt:Unit INSTANCES, invisible to an owl:Class-only index.
        assertTrue("QUDT terms must index (qudt:QuantityKind / qudt:Unit instances)",
                anyIriContains(index, "qudt.org/vocab"));
    }

    @Test
    public void testForceQuantityKindSearchable() {
        ConceptIndex index = loadBundle();
        // "Force" is a canonical QUDT quantity kind - proves a MOE/TPM-relevant measure is
        // present and label-searchable.
        assertTrue("QUDT 'Force' quantity kind should be indexed by label",
                anyLabelEquals(index, "Force"));
    }
}
