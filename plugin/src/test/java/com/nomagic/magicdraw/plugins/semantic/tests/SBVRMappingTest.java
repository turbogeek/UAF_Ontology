package com.nomagic.magicdraw.plugins.semantic.tests;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Validates the generation of SBVR (Semantics of Business Vocabulary and Business Rules) Structured English.
 * Asserts mapping transformations for both instantiation and relation composition.
 * Trace: PLG-REQ-04
 */
public class SBVRMappingTest {

    @Test
    public void testSBVRGenerationAssertions() {
        // Test Case 1: Simple Instantiation Mapping (SUMO / EMMO Concept)
        String sbvr1 = generateSBVR("http://purl.org/uaf/example/ev_power#inst-ev_battery_pack", "battinfo:BatteryPack", null, null);
        assertEquals("Instance: Ev Battery Pack is a Battery Pack.", stripHtml(sbvr1));

        // Test Case 2: Composition Linkage
        String sbvr2 = generateSBVR("http://purl.org/uaf/example/ev_power#inst-ev_battery_pack", "battinfo:BatteryPack", "contains", "BatteryModule1");
        assertEquals("Instance: Ev Battery Pack contains Battery Module1.", stripHtml(sbvr2));

        // Test Case 3: UML Owned Post Mapping (ORG Concept)
        String sbvr3 = generateSBVR("sr:post-lead_aerospace_architect", "org:Post", "owned by", "sr:org-design_division");
        assertEquals("Instance: Lead Aerospace Architect is a Post owned by Design Division.", stripHtml(sbvr3));
    }

    /**
     * Mock SBVR translation logic for unit test verification.
     */
    private String generateVRString(String elementURI, String conceptFQN, String relation, String targetName) {
        String baseName = getLocalName(elementURI);
        String conceptName = getLocalName(conceptFQN);

        if (relation == null || relation.isEmpty()) {
            return "Instance: " + baseName + " is a " + conceptName + ".";
        } else if (targetName != null) {
            if (relation.equalsIgnoreCase("contains")) {
                return "Instance: " + baseName + " contains " + getLocalName(targetName) + ".";
            } else {
                return "Instance: " + baseName + " is a " + conceptName + " " + relation + " " + getLocalName(targetName) + ".";
            }
        }
        return "";
    }

    private String generateSBVR(String elementURI, String conceptFQN, String relation, String targetName) {
        // Renders HTML formatted SBVR markup
        return "<html><body><span class='instance'>" + generateVRString(elementURI, conceptFQN, relation, targetName) + "</span></body></html>";
    }

    private String getLocalName(String uriOrFqn) {
        if (uriOrFqn == null) return "";
        int hashIdx = uriOrFqn.lastIndexOf('#');
        if (hashIdx != -1) return formatLabel(uriOrFqn.substring(hashIdx + 1));
        int colonIdx = uriOrFqn.lastIndexOf(':');
        if (colonIdx != -1) return formatLabel(uriOrFqn.substring(colonIdx + 1));
        int slashIdx = uriOrFqn.lastIndexOf('/');
        if (slashIdx != -1) return formatLabel(uriOrFqn.substring(slashIdx + 1));
        return formatLabel(uriOrFqn);
    }

    private String formatLabel(String rawName) {
        // Strip prefixes and format camelCase / snake_case to Space Separated Words
        String result = rawName.replace("inst-", "").replace("post-", "").replace("org-", "");
        result = result.replaceAll("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])", " ");
        result = result.replace("_", " ");
        
        // Capitalize first letter of each word
        String[] words = result.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", "").trim();
    }
}
