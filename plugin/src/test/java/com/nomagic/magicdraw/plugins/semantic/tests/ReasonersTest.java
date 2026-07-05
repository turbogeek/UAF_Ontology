package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.reasoning.JenaRulesReasonerAdapter;
import com.nomagic.magicdraw.plugins.semantic.reasoning.ReasonerAdapter;
import com.nomagic.magicdraw.plugins.semantic.reasoning.Reasoners;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validates the engine-agnostic reasoner seam (owner decision: non-denominational
 * reasoning; engines are selected, benchmarked, and swapped via a JVM property).
 * Trace: v3 plan section 4, PLG-REQ-06
 */
public class ReasonersTest {

    @After
    public void clearSelection() {
        System.clearProperty("semantic.plugin.reasoner");
    }

    private Model model(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(
                turtle.getBytes(StandardCharsets.UTF_8)), Lang.TTL);
        return model;
    }

    @Test
    public void testDefaultEngineIsJenaRules() {
        assertEquals(JenaRulesReasonerAdapter.ID, Reasoners.active().id());
    }

    @Test
    public void testUnknownEngineFallsBackLoudlyNotFatally() {
        System.setProperty("semantic.plugin.reasoner", "no-such-engine");
        assertEquals("unknown id must fall back to the default engine",
                JenaRulesReasonerAdapter.ID, Reasoners.active().id());
    }

    @Test
    public void testAdapterSeparatesAboxFromTbox() {
        Model tbox = model("""
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix ex:  <http://example.org/> .
                ex:Logical a owl:Class . ex:Actual a owl:Class .
                ex:Logical owl:disjointWith ex:Actual .
                """);
        Model badAbox = model("""
                @prefix ex: <http://example.org/> .
                ex:thing a ex:Logical , ex:Actual .
                """);
        Model goodAbox = model("""
                @prefix ex: <http://example.org/> .
                ex:thing a ex:Logical .
                """);
        ReasonerAdapter engine = Reasoners.active();
        ReasonerAdapter.ConsistencyResult bad = engine.checkConsistency(badAbox, tbox);
        assertFalse("disjoint dual-typing must be inconsistent", bad.consistent());
        assertFalse("explanations required", bad.messages().isEmpty());
        assertTrue(engine.checkConsistency(goodAbox, tbox).consistent());
        assertTrue("empty TBox must not fail",
                engine.checkConsistency(goodAbox, ModelFactory.createDefaultModel()).consistent());
    }
}
