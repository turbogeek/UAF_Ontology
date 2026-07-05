package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates SBVR Structured English generation against the production SBVREngine for all
 * eight formal mapping scenarios of design spec section 10.1 (SC-01 .. SC-08).
 *
 * Formatting convention: the engine renders camelCase and snake_case identifiers as
 * space-separated Title Case words ("EchoBase" -> "Echo Base"), which is asserted here
 * as the single authoritative behavior.
 * Trace: PLG-REQ-04
 */
public class SBVRMappingTest {

    private final SBVREngine engine = new SBVREngine();

    @Test
    public void testSC01SimpleInstantiation() {
        String sbvr = engine.generateSBVR(
                "http://purl.org/uaf/example/ev_power#EchoBase", "sumo:MilitaryBase", null, null);
        assertEquals("Instance: Echo Base is a Military Base.", stripHtml(sbvr));
    }

    @Test
    public void testSC02Composition() {
        String sbvr = engine.generateSBVR(
                "http://purl.org/uaf/example/ev_power#BatteryPack", "battinfo:BatteryModule",
                "contains", "Module1");
        assertEquals("Instance: Battery Pack contains Module1.", stripHtml(sbvr));
    }

    @Test
    public void testSC03AssociationWithRelationAsConcept() {
        // The aligned concept IS the relation (sr:connectedTo); no "is a" clause expected.
        String sbvr = engine.generateSBVR("Transmitter", "sr:connectedTo", "connected to", "Receiver");
        assertEquals("Instance: Transmitter connected to Receiver.", stripHtml(sbvr));
    }

    @Test
    public void testSC04OwnedPostMapping() {
        String sbvr = engine.generateSBVR(
                "sr:post-lead_aerospace_architect", "org:Post", "owned by", "sr:org-design_division");
        assertEquals("Instance: Lead Aerospace Architect is a Post owned by Design Division.", stripHtml(sbvr));
    }

    @Test
    public void testSC05Generalization() {
        String sbvr = engine.generateSBVR("AT-AT", "sumo:LandVehicle", "specializes", null);
        assertEquals("Concept: AT-AT is a kind of Land Vehicle.", stripHtml(sbvr));
    }

    @Test
    public void testSC06RequirementRefinement() {
        // Relation with no explicit target: the aligned concept is the relation target.
        String sbvr = engine.generateSBVR(
                "ic:TempControlRequirement", "ic:ActivePreservationCapability", "refines", null);
        assertEquals("Instance: Temp Control Requirement refines Active Preservation Capability.",
                stripHtml(sbvr));
    }

    @Test
    public void testSC07StandardConformance() {
        String sbvr = engine.generateSBVR(
                "sr:PropulsionMfgTeam", "sr:conformsTo", "conforms to", "ISO 9001");
        assertEquals("Instance: Propulsion Mfg Team conforms to ISO 9001.", stripHtml(sbvr));
    }

    @Test
    public void testSC08GoalChanneling() {
        // Base name already ends with the concept name ("... Goal" / bmm:Goal), so the
        // redundant type clause is suppressed.
        String sbvr = engine.generateSBVR(
                "ic:PreventSpoilageGoal", "bmm:Goal", "channels efforts towards", "InsulinCooler");
        assertEquals("Instance: Prevent Spoilage Goal channels efforts towards Insulin Cooler.",
                stripHtml(sbvr));
    }

    @Test
    public void testSnakeCaseInstantiation() {
        String sbvr = engine.generateSBVR(
                "http://purl.org/uaf/example/ev_power#inst-ev_battery_pack", "battinfo:BatteryPack", null, null);
        assertEquals("Instance: Ev Battery Pack is a Battery Pack.", stripHtml(sbvr));
    }

    @Test
    public void testHtmlWrapperPresent() {
        String sbvr = engine.generateSBVR("EchoBase", "sumo:MilitaryBase", null, null);
        assertTrue("SBVR output must be HTML-wrapped for the sidebar view",
                sbvr.startsWith("<html>") && sbvr.endsWith("</html>"));
    }

    @Test
    public void testNullInputsProduceEmptyNames() {
        // Defensive contract: nulls must not throw, they degrade to empty name tokens.
        String sbvr = engine.generatePlainSBVR(null, null, null, null);
        assertEquals("Instance:  is a .", sbvr);
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", "").trim();
    }
}
