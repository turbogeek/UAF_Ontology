package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.ConceptCategoryIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.LayerRouter;
import com.nomagic.magicdraw.plugins.semantic.align.ScopeContext;
import com.nomagic.magicdraw.plugins.semantic.align.StereotypeRouter;
import com.nomagic.magicdraw.plugins.semantic.align.SuggestionRanker;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDFS;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic, service-free proof of scope-aware ranking (UC-2.8) over a tiny synthetic BFO
 * model + concept index. It also exercises {@link ConceptCategoryIndex#build} against real
 * {@code rdfs:subClassOf*} traversal, so the construct-kind classifier is covered too. The three
 * signals mirror what was verified live against the standalone service:
 * <ul>
 *   <li>context terms disambiguate same-base siblings (aircraft -> Jet Engine, steam -> Steam Engine);</li>
 *   <li>construct kind overrides a wrong-category exact match (Activity -> "Act of Government"
 *       over the exact object "Government");</li>
 *   <li>UAF layer boosts the layer's namespaces (Resource -> cco Agent over prov Agent).</li>
 * </ul>
 */
public class ScopeAwareRankingTest {

    private static final String BFO = "http://purl.obolibrary.org/obo/";
    private static final String OCCURRENT = BFO + "BFO_0000003";
    private static final String PROCESS = BFO + "BFO_0000015";
    private static final String INDEP_CONTINUANT = BFO + "BFO_0000004";
    private static final String EX = "http://example.org/ont#";

    /** Builds the BFO grounding model: engines + government + agents under the right anchors. */
    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        Resource occurrent = m.createResource(OCCURRENT);
        Resource process = m.createResource(PROCESS);
        Resource object = m.createResource(INDEP_CONTINUANT);
        process.addProperty(RDFS.subClassOf, occurrent);
        // Objects (continuants)
        Resource engine = res(m, "Engine", object);
        res(m, "JetEngine", engine);
        res(m, "SteamEngine", engine);
        res(m, "Government", object);
        res(m, "AgentCco", object);
        res(m, "AgentProv", object);
        // Occurrent (process)
        res(m, "ActOfGovernment", process);
        return m;
    }

    private static Resource res(Model m, String local, Resource superClass) {
        Resource r = m.createResource(EX + local);
        r.addProperty(RDFS.subClassOf, superClass);
        return r;
    }

    private static ConceptEntry entry(String local, String prefix, String label, String comment) {
        return new ConceptEntry(EX + local, prefix + ":" + local, label, List.of(), comment,
                "test", prefix, ConceptIndex.tokenize(label));
    }

    private static SuggestionRanker ranker() {
        ConceptIndex index = new ConceptIndex();
        index.add(entry("Engine", "cco", "Engine", "A machine that converts energy into motion."));
        index.add(entry("JetEngine", "cco", "Jet Engine",
                "An engine that propels an aircraft using a jet of exhaust."));
        index.add(entry("SteamEngine", "cco", "Steam Engine",
                "An engine used in a locomotive that is powered by steam."));
        index.add(entry("Government", "cco", "Government", "The governing body of a nation or state."));
        index.add(entry("ActOfGovernment", "cco", "Act of Government",
                "A process of governing carried out by a government."));
        index.add(entry("AgentCco", "cco", "Agent", "An entity that acts."));
        index.add(entry("AgentProv", "provo", "Agent", "An agent in the provenance sense."));

        ConceptCategoryIndex categories = ConceptCategoryIndex.build(model());
        Properties layers = new Properties();
        layers.setProperty("RESOURCE", "cco,sumo,qudt");
        return new SuggestionRanker(index, StereotypeRouter.fromProperties(new Properties()),
                categories, LayerRouter.fromProperties(layers));
    }

    private static int rankOf(List<ConceptSuggestion> hits, String curie) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).entry().curie().equals(curie)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static double scoreOf(List<ConceptSuggestion> hits, String curie) {
        for (ConceptSuggestion h : hits) {
            if (h.entry().curie().equals(curie)) {
                return h.score();
            }
        }
        return -1;
    }

    @Test
    public void testBfoClassifierSeparatesOccurrentFromObject() {
        ConceptCategoryIndex cats = ConceptCategoryIndex.build(model());
        assertEquals(ConceptCategoryIndex.Category.OBJECT, cats.categoryOf(EX + "JetEngine"));
        assertEquals(ConceptCategoryIndex.Category.OBJECT, cats.categoryOf(EX + "Government"));
        assertEquals(ConceptCategoryIndex.Category.OCCURRENT, cats.categoryOf(EX + "ActOfGovernment"));
        assertEquals(ConceptCategoryIndex.Category.UNKNOWN, cats.categoryOf(EX + "Nonexistent"));
    }

    @Test
    public void testContextTermDisambiguatesEngineSiblings() {
        SuggestionRanker r = ranker();
        ScopeContext aircraft = new ScopeContext(null, ScopeContext.STRUCTURE,
                List.of(new ScopeContext.ContextTerm("aircraft", ScopeContext.Role.OWNER),
                        new ScopeContext.ContextTerm("jet", ScopeContext.Role.TYPE)));
        List<ConceptSuggestion> hits = r.searchVariants("engine", List.of(), null, 10, aircraft);
        assertTrue("Jet Engine outranks Steam Engine in an aircraft context",
                rankOf(hits, "cco:JetEngine") < rankOf(hits, "cco:SteamEngine"));

        ScopeContext steam = new ScopeContext(null, ScopeContext.STRUCTURE,
                List.of(new ScopeContext.ContextTerm("locomotive", ScopeContext.Role.OWNER),
                        new ScopeContext.ContextTerm("steam", ScopeContext.Role.TYPE)));
        List<ConceptSuggestion> hits2 = r.searchVariants("engine", List.of(), null, 10, steam);
        assertTrue("Steam Engine outranks Jet Engine in a locomotive/steam context",
                rankOf(hits2, "cco:SteamEngine") < rankOf(hits2, "cco:JetEngine"));
    }

    @Test
    public void testConstructKindOverridesWrongCategoryExactMatch() {
        SuggestionRanker r = ranker();
        // BEHAVIOR: the process "Act of Government" must beat the exact object "Government".
        ScopeContext behavior = new ScopeContext(null, ScopeContext.BEHAVIOR, List.of());
        List<ConceptSuggestion> b = r.searchVariants("government", List.of(), null, 10, behavior);
        assertTrue("Act of Government (occurrent) outranks the exact object Government for a behavior",
                rankOf(b, "cco:ActOfGovernment") < rankOf(b, "cco:Government"));

        // STRUCTURE: the exact object "Government" must lead.
        ScopeContext structure = new ScopeContext(null, ScopeContext.STRUCTURE, List.of());
        List<ConceptSuggestion> s = r.searchVariants("government", List.of(), null, 10, structure);
        assertTrue("Government (object) outranks Act of Government for a structure",
                rankOf(s, "cco:Government") < rankOf(s, "cco:ActOfGovernment"));
    }

    @Test
    public void testUafLayerBoostsLayerNamespaces() {
        SuggestionRanker r = ranker();
        // Without a layer, the two "Agent" concepts (cco, provo) score identically.
        List<ConceptSuggestion> flat = r.searchVariants("agent", List.of(), null, 10, ScopeContext.EMPTY);
        assertEquals("cco and prov Agent tie without a layer",
                scoreOf(flat, "cco:AgentCco"), scoreOf(flat, "provo:AgentProv"), 0.0001);

        // Resource layer boosts the cco namespace above prov.
        ScopeContext resource = new ScopeContext("RESOURCE", null, List.of());
        List<ConceptSuggestion> boosted = r.searchVariants("agent", List.of(), null, 10, resource);
        assertTrue("Resource layer lifts cco Agent above prov Agent",
                scoreOf(boosted, "cco:AgentCco") > scoreOf(boosted, "provo:AgentProv"));
    }

    @Test
    public void testEmptyScopeReproducesBaseline() {
        SuggestionRanker r = ranker();
        List<ConceptSuggestion> scoped = r.searchVariants("engine", List.of(), null, 10, ScopeContext.EMPTY);
        List<ConceptSuggestion> plain = r.searchVariants("engine", List.of(), null, 10);
        assertEquals("empty scope == non-scoped overload", plain.size(), scoped.size());
        assertEquals(scoreOf(plain, "cco:Engine"), scoreOf(scoped, "cco:Engine"), 0.0001);
    }
}
