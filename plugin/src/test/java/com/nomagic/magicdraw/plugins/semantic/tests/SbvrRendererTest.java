package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.view.SbvrRenderer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates the Muggle-first English rendering of an exported ontology: every fact
 * becomes one readable sentence, labels win over IRI local names, and the display cap
 * bounds cost on large graphs (dynamic display-scale requirement).
 * Trace: v3 plan section 2
 */
public class SbvrRendererTest {

    private Model model(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(
                turtle.getBytes(StandardCharsets.UTF_8)), Lang.TTL);
        return model;
    }

    private static final String FIXTURE = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix uaf:  <http://purl.org/uaf/ontology#> .
            @prefix sumo: <http://www.ontologyportal.org/SUMO.owl#> .
            @prefix p:    <http://purl.org/uaf/project/> .
            p:e1 a sumo:MilitaryBase ; rdfs:label "EchoBase" ;
                 uaf:hasPart p:e2 ; uaf:connectedTo p:e3 .
            p:e2 a sumo:Device ; rdfs:label "IonCannonControl" .
            p:e3 a sumo:Vehicle ; rdfs:label "TransportShip" .
            """;

    @Test
    public void testFactsBecomeEnglishSentences() {
        SbvrRenderer.Rendering rendering = new SbvrRenderer().render(model(FIXTURE), 100);
        String all = String.join("\n", rendering.sentences());
        assertTrue("type sentence expected:\n" + all,
                all.contains("Instance: Echo Base is a Military Base."));
        assertTrue("containment sentence expected:\n" + all,
                all.contains("Instance: EchoBase has part IonCannonControl."));
        assertTrue("association sentence expected:\n" + all,
                all.contains("Instance: EchoBase connected to TransportShip."));
    }

    @Test
    public void testDisplayCapBoundsCost() {
        StringBuilder big = new StringBuilder("@prefix p: <http://purl.org/uaf/project/> .\n"
                + "@prefix s: <http://example.org/s#> .\n");
        for (int i = 0; i < 300; i++) {
            big.append("p:e").append(i).append(" a s:Thing .\n");
        }
        SbvrRenderer.Rendering rendering = new SbvrRenderer().render(model(big.toString()), 50);
        assertEquals("cap must bound the rendered list", 50, rendering.sentences().size());
        assertEquals("total must still report the full fact count", 300, rendering.totalFacts());
    }

    @Test
    public void testRelationHumanization() {
        assertEquals("has part", SbvrRenderer.humanizeRelation("hasPart"));
        assertEquals("connected to", SbvrRenderer.humanizeRelation("connectedTo"));
        assertEquals("channels efforts towards", SbvrRenderer.humanizeRelation("channelsEffortsTowards"));
    }
}
