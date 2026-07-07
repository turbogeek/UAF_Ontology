package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.RelationVocabulary;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The curated relation vocabulary (owner choice) feeds the compound-concept relation dropdown.
 * Verifies the classpath default loads a non-empty, ordered, comment-free list with the expected
 * SE relation phrases.
 */
public class RelationVocabularyTest {

    private final RelationVocabulary vocab = RelationVocabulary.defaults();

    @Test
    public void testDefaultsLoadOrderedPhrases() {
        assertFalse("default vocabulary must not be empty", vocab.isEmpty());
        assertTrue(vocab.phrases().contains("has function"));
        assertTrue(vocab.phrases().contains("is part of"));
        assertTrue(vocab.phrases().contains("targets"));
        // "has function" is listed before "is part of" in the file -> order preserved.
        assertTrue(vocab.phrases().indexOf("has function") < vocab.phrases().indexOf("is part of"));
    }

    @Test
    public void testNoCommentsOrBlankLines() {
        for (String p : vocab.phrases()) {
            assertFalse("no comment lines", p.startsWith("#"));
            assertFalse("no blank phrases", p.isBlank());
        }
    }
}
