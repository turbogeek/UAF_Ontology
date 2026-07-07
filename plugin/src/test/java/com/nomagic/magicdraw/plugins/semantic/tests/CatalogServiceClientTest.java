package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.ScopeContext;
import com.nomagic.magicdraw.plugins.semantic.align.UafConceptResolver;
import com.nomagic.magicdraw.plugins.semantic.rest.CatalogServiceClient;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertTrue;

/**
 * End-to-end contract test for the thin CatalogServiceClient against a RUNNING out-of-process
 * Semantic Catalog Service on localhost:8767. Skipped when the service is not running (so it is
 * a no-op in normal CI). Start the service with:
 *   java -cp "build/service/semantic-catalog-service.jar;build/service/lib/*" \
 *        com.nomagic.magicdraw.plugins.semantic.service.SemanticCatalogService \
 *        --port 8767 --plugin-dir "&lt;deployed-plugin-dir&gt;"
 * Trace: design/service_architecture.md
 */
public class CatalogServiceClientTest {

    private CatalogServiceClient client() {
        CatalogServiceClient c = new CatalogServiceClient(
                System.getProperty("semantic.test.service.url", "http://127.0.0.1:8767"));
        Assume.assumeTrue("catalog service not running - skipping client contract test", c.isReady());
        return c;
    }

    @Test
    public void testSuggestReturnsPinnedUafBaseWithSbvr() {
        CatalogServiceClient c = client();
        List<ConceptSuggestion> hits = c.suggest("Extreme Weather Conditions",
                List.of("Challenge"), "Challenge", 5);
        assertTrue("expected suggestions", !hits.isEmpty());
        assertTrue("uaf:Challenge should be present",
                hits.stream().anyMatch(s -> s.entry().iri() != null
                        && s.entry().iri().toLowerCase().contains("challenge")));
        assertTrue("suggestions carry an SBVR sentence",
                hits.stream().anyMatch(s -> s.sbvr() != null && !s.sbvr().isBlank()));
    }

    @Test
    public void testSearchLocalFindsQudtForce() {
        CatalogServiceClient c = client();
        List<ConceptSuggestion> hits = c.search("Force", null, 6, false);
        assertTrue("QUDT/CCO Force concept should surface",
                hits.stream().anyMatch(s -> s.entry().iri() != null
                        && s.entry().iri().contains("qudt.org")));
    }

    @Test
    public void testScopeAwareContextDisambiguatesEngine() {
        CatalogServiceClient c = client();
        // Same query word "engine", two different owner/type contexts -> different top sibling.
        ScopeContext aircraft = new ScopeContext(null, ScopeContext.STRUCTURE,
                List.of(new ScopeContext.ContextTerm("aircraft", ScopeContext.Role.OWNER),
                        new ScopeContext.ContextTerm("jet", ScopeContext.Role.TYPE)));
        List<ConceptSuggestion> air = c.suggest("engine", List.of(), null, 10, aircraft);
        int jet = indexOfLabelContains(air, "jet engine");
        int steam = indexOfLabelContains(air, "steam engine");
        assertTrue("Jet Engine present in an aircraft context", jet >= 0);
        assertTrue("Jet Engine outranks Steam Engine in an aircraft context",
                steam < 0 || jet < steam);
    }

    @Test
    public void testScopeAwareConstructKindPrefersProcess() {
        CatalogServiceClient c = client();
        ScopeContext behavior = new ScopeContext(null, ScopeContext.BEHAVIOR, List.of());
        List<ConceptSuggestion> b = c.suggest("government", List.of(), null, 12, behavior);
        int act = indexOfLabelContains(b, "act of government");
        int gov = indexOfExactLabel(b, "government");
        assertTrue("Act of Government present for a behavior", act >= 0);
        assertTrue("Act of Government (process) outranks the exact object Government for a behavior",
                gov < 0 || act < gov);
    }

    private static int indexOfLabelContains(List<ConceptSuggestion> hits, String needle) {
        for (int i = 0; i < hits.size(); i++) {
            String label = hits.get(i).entry().label();
            if (label != null && label.toLowerCase().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfExactLabel(List<ConceptSuggestion> hits, String label) {
        for (int i = 0; i < hits.size(); i++) {
            String l = hits.get(i).entry().label();
            if (l != null && l.equalsIgnoreCase(label)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void testResolveReturnsBaseConcept() {
        CatalogServiceClient c = client();
        Optional<UafConceptResolver.UafConcept> concept = c.resolve("Challenge", null);
        assertTrue("Challenge should resolve to a UAF concept", concept.isPresent());
        assertTrue(concept.get().iri().toLowerCase().contains("challenge"));
    }
}
