package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.QueryVariants;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates multi-term query decomposition: a multi-word element name plus its stereotype
 * term must expand into full-phrase / sub-phrase / word / synonym variants, ordered by tier
 * weight (owner scenario: "Extreme Weather Conditions" as a UAF Challenge).
 * Trace: design/use_cases.md UC-2.2
 */
public class QueryVariantsTest {

    private static List<String> texts(List<QueryVariants.Variant> vs) {
        return vs.stream().map(v -> v.text().toLowerCase(Locale.ROOT)).toList();
    }

    private static double weightOf(List<QueryVariants.Variant> vs, String text) {
        return vs.stream().filter(v -> v.text().equalsIgnoreCase(text))
                .mapToDouble(QueryVariants.Variant::tierWeight).findFirst().orElse(-1);
    }

    @Test
    public void testGeneratesFullSubphraseAndWordVariants() {
        List<QueryVariants.Variant> vs = QueryVariants.generate(
                "Extreme Weather Conditions", List.of("Challenge"));
        List<String> t = texts(vs);
        assertTrue("stereotype+full phrase", t.contains("challenge extreme weather conditions"));
        assertTrue("full phrase", t.contains("extreme weather conditions"));
        assertTrue("leading sub-phrase", t.contains("extreme weather"));
        assertTrue("trailing sub-phrase", t.contains("weather conditions"));
        assertTrue("word extreme", t.contains("extreme"));
        assertTrue("word weather", t.contains("weather"));
        assertTrue("word conditions", t.contains("conditions"));
        assertTrue("stereotype word", t.contains("challenge"));
        // The stereotype-prefixed full phrase is the highest tier.
        assertEquals(1.0, vs.get(0).tierWeight(), 0.001);
    }

    @Test
    public void testWeightsDescendFullOverSubOverWord() {
        List<QueryVariants.Variant> vs = QueryVariants.generate("Extreme Weather Conditions", List.of());
        double full = weightOf(vs, "extreme weather conditions");
        double sub = weightOf(vs, "extreme weather");
        double word = weightOf(vs, "weather");
        assertTrue("full > sub-phrase", full > sub);
        assertTrue("sub-phrase > word", sub > word);
    }

    @Test
    public void testSingleWordNameYieldsWordAndSynonyms() {
        List<QueryVariants.Variant> vs = QueryVariants.generate("Weather", List.of());
        List<String> t = texts(vs);
        assertTrue(t.contains("weather"));
        // curated synonym expansion so a meteorology ontology concept can still surface
        assertTrue("synonym expansion", t.contains("meteorological") || t.contains("climatic"));
    }

    @Test
    public void testDeduplicatesAndBounds() {
        List<QueryVariants.Variant> vs = QueryVariants.generate("Weather Weather Weather", List.of());
        long distinct = texts(vs).stream().distinct().count();
        assertEquals("no duplicate variant text", distinct, vs.size());
        assertTrue("bounded", vs.size() <= 40);
    }

    @Test
    public void testEmptyInputYieldsNoVariants() {
        assertTrue(QueryVariants.generate("", List.of()).isEmpty());
        assertTrue(QueryVariants.generate(null, List.of()).isEmpty());
    }
}
