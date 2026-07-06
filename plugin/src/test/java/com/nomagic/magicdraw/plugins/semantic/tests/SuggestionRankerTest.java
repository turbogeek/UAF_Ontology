package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.StereotypeRouter;
import com.nomagic.magicdraw.plugins.semantic.align.SuggestionRanker;
import org.junit.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates the suggestion engine that replaces CURIE typing: tokenization, stereotype
 * routing with exact-concept pins (the Tutorial A mapping table), and ranking order.
 * Trace: v3 plan section 1, click budget 2 clicks / 0 keystrokes
 */
public class SuggestionRankerTest {

    private static ConceptEntry entry(String prefix, String local, String label) {
        return new ConceptEntry(
                "http://example.org/" + prefix + "#" + local,
                prefix + ":" + local, label, List.of(), "doc of " + label,
                prefix, prefix, ConceptIndex.tokenize(label + " " + local));
    }

    private ConceptIndex index() {
        ConceptIndex index = new ConceptIndex();
        index.add(entry("org", "Organization", "Organization"));
        index.add(entry("org", "OrganizationalUnit", "Organizational Unit"));
        index.add(entry("org", "Post", "Post"));
        index.add(entry("org", "Membership", "Membership"));
        index.add(entry("bmm", "Goal", "Goal"));
        index.add(entry("sumo", "LandVehicle", "Land Vehicle"));
        index.add(entry("sumo", "MilitaryBase", "Military Base"));
        return index;
    }

    private StereotypeRouter router() {
        Properties properties = new Properties();
        properties.setProperty("ActualOrganization", "org:Organization,org");
        properties.setProperty("EnterpriseGoal", "bmm:Goal,bmm");
        return StereotypeRouter.fromProperties(properties);
    }

    @Test
    public void testTokenizeSplitsCamelCaseAndSeparators() {
        assertEquals(Set.of("battery", "design", "team"), ConceptIndex.tokenize("BatteryDesignTeam"));
        assertEquals(Set.of("lead", "battery", "chemist"), ConceptIndex.tokenize("lead_battery-chemist"));
        assertEquals(Set.of("at"), ConceptIndex.tokenize("AT-AT"));
    }

    @Test
    public void testZeroKeystrokePinBeatsAlphabeticalTie() {
        // ResearchDivision shares no tokens with any org concept; without the pin the
        // routed namespace would tie and alphabetics would rank Membership first.
        SuggestionRanker ranker = new SuggestionRanker(index(), router());
        List<ConceptSuggestion> top = ranker.suggestForElement(
                "ResearchDivision", List.of("ActualOrganization"), 5);
        assertTrue("expected suggestions", !top.isEmpty());
        assertEquals("org:Organization", top.get(0).entry().curie());
    }

    @Test
    public void testExactLabelMatchOutranksPin() {
        SuggestionRanker ranker = new SuggestionRanker(index(), router());
        List<ConceptSuggestion> top = ranker.search(
                "Military Base", "EchoBase", List.of("ActualOrganization"), 5);
        assertEquals("sumo:MilitaryBase", top.get(0).entry().curie());
        assertTrue("exact match must score high", top.get(0).score() > 0.5);
    }

    @Test
    public void testTypedPrefixNarrows() {
        SuggestionRanker ranker = new SuggestionRanker(index(), router());
        List<ConceptSuggestion> top = ranker.search("organiz", "X", List.of(), 5);
        assertTrue(top.size() >= 2);
        assertTrue("both organization concepts surface",
                top.stream().anyMatch(s -> s.entry().curie().equals("org:Organization")));
        assertTrue(top.stream().anyMatch(s -> s.entry().curie().equals("org:OrganizationalUnit")));
        // Nothing unrelated sneaks in ahead of them
        assertTrue(top.get(0).entry().curie().startsWith("org:"));
    }

    @Test
    public void testElementNameTokensBoostWithoutTyping() {
        SuggestionRanker ranker = new SuggestionRanker(index(), null);
        List<ConceptSuggestion> top = ranker.suggestForElement("GoalOfEvacuation", List.of(), 5);
        assertEquals("name token 'goal' must find bmm:Goal with no routing at all",
                "bmm:Goal", top.get(0).entry().curie());
    }

    @Test
    public void testEmptyIndexYieldsEmptyNotCrash() {
        SuggestionRanker ranker = new SuggestionRanker(new ConceptIndex(), router());
        assertTrue(ranker.suggestForElement("Anything", List.of("ActualOrganization"), 5).isEmpty());
    }

    // ---- Multi-term decomposition (searchVariants) ----------------------------------------

    private ConceptIndex weatherIndex() {
        ConceptIndex index = new ConceptIndex();
        index.add(entry("cg", "ExtremeWeatherConditions", "Extreme Weather Conditions")); // exact full phrase
        index.add(entry("met", "ExtremeWeather", "Extreme Weather"));                      // sub-phrase
        index.add(entry("gen", "Weather", "Weather"));                                     // single word
        index.add(entry("gen", "Extreme", "Extreme"));                                     // single word
        index.add(entry("gen", "Conditions", "Conditions"));                               // single word
        index.add(entry("cg", "Challenge", "Challenge"));                                  // stereotype term
        index.add(entry("noise", "Membership", "Membership"));                             // unrelated
        return index;
    }

    private static int indexOf(List<ConceptSuggestion> list, String curie) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).entry().curie().equals(curie)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void testSearchVariantsFullPhraseOutranksSubphraseOutranksWord() {
        SuggestionRanker ranker = new SuggestionRanker(weatherIndex(), null);
        List<ConceptSuggestion> top = ranker.searchVariants(
                "Extreme Weather Conditions", List.of(), null, 10);
        assertTrue("expected several results", top.size() >= 3);
        // The exact full-phrase concept (e.g. a Coast Guard ontology) wins - best match first.
        assertEquals("cg:ExtremeWeatherConditions", top.get(0).entry().curie());
        int subIdx = indexOf(top, "met:ExtremeWeather");
        int wordIdx = indexOf(top, "gen:Weather");
        assertTrue("sub-phrase concept present", subIdx >= 0);
        assertTrue("single-word concept present", wordIdx >= 0);
        assertTrue("sub-phrase must outrank single word", subIdx < wordIdx);
    }

    @Test
    public void testSearchVariantsPopulatesMatchedVariantAndContext() {
        SuggestionRanker ranker = new SuggestionRanker(weatherIndex(), null);
        List<ConceptSuggestion> top = ranker.searchVariants(
                "Extreme Weather Conditions", List.of(), null, 10);
        assertTrue(!top.isEmpty());
        // Variants are built from normalized (lowercased) tokens, so the matched-variant hint
        // is case-insensitive to the original phrase.
        assertTrue("matched variant is the full phrase for the exact hit",
                "extreme weather conditions".equalsIgnoreCase(top.get(0).matchedVariant()));
        assertTrue("context snippet attached", top.get(0).context() != null
                && !top.get(0).context().isBlank());
    }

    @Test
    public void testSearchVariantsSearchesStereotypeTermAsText() {
        SuggestionRanker ranker = new SuggestionRanker(weatherIndex(), null);
        // The stereotype term "Challenge" is decomposed into the query, so cg:Challenge surfaces.
        List<ConceptSuggestion> top = ranker.searchVariants(
                "Extreme Weather Conditions", List.of("Challenge"), null, 10);
        assertTrue("stereotype-term concept must surface",
                top.stream().anyMatch(s -> s.entry().curie().equals("cg:Challenge")));
    }

    @Test
    public void testSearchVariantsEmptyOnNoMatch() {
        SuggestionRanker ranker = new SuggestionRanker(weatherIndex(), null);
        // Nothing in the weather index tokenizes to "quantum", so an honest empty result.
        assertTrue(ranker.searchVariants("Quantum", List.of(), null, 10).isEmpty());
    }
}
