package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import com.nomagic.magicdraw.plugins.semantic.align.CompoundConcept;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A compound concept (genus + differentia) must READ CORRECTLY in SBVR - the owner's requirement.
 * Verifies parse/serialize over the existing multi-valued mappedConceptURI encoding (index 0 =
 * genus, later values = "relation | iri" or a bare type) and the composed SBVR sentence.
 */
public class CompoundConceptTest {

    private static final String EX = "http://example.org/pest#";
    private final SBVREngine sbvr = new SBVREngine();

    @Test
    public void testCompoundReadsCorrectlyInSbvr() {
        List<String> stored = List.of(
                EX + "Drone",
                "suppresses" + CompoundConcept.SEP + EX + "Mosquito",
                "uses" + CompoundConcept.SEP + EX + "ChemicalSprayer");
        CompoundConcept cc = CompoundConcept.parse("MosquitoSuppressionDrone", stored);
        assertTrue("has relation clauses -> compound", cc.isCompound());
        String s = cc.toSbvr(sbvr);
        // "Concept: A Mosquito Suppression Drone is a Drone that suppresses a Mosquito and uses a Chemical Sprayer."
        assertTrue(s, s.contains("A Mosquito Suppression Drone is a Drone"));
        assertTrue(s, s.contains("that suppresses a Mosquito"));
        assertTrue(s, s.contains("and uses a Chemical Sprayer"));
        assertTrue("ends with a period", s.trim().endsWith("."));
    }

    @Test
    public void testFlatAlignmentIsNotCompoundAndStillReads() {
        CompoundConcept cc = CompoundConcept.parse("SearchDrone", List.of(EX + "Drone"));
        assertFalse(cc.isCompound());
        String s = cc.toSbvr(sbvr);
        assertTrue(s, s.contains("A Search Drone is a Drone"));
    }

    @Test
    public void testBlankRelationRendersAsIsA() {
        // A bare extra type (no relation) verbalizes as "... and is a Aircraft".
        List<String> stored = List.of(EX + "Drone", EX + "Aircraft");
        String s = CompoundConcept.parse("HybridDrone", stored).toSbvr(sbvr);
        assertTrue(s, s.contains("is a Drone"));
        assertTrue(s, s.contains("is an Aircraft")); // article agrees with the vowel
    }

    @Test
    public void testStorageRoundTrip() {
        List<String> stored = List.of(
                EX + "Drone",
                "targets" + CompoundConcept.SEP + EX + "Mosquito");
        CompoundConcept cc = CompoundConcept.parse("X", stored);
        assertEquals("round-trips through the tag encoding", stored, cc.toStoredList());
    }

    @Test
    public void testDecodeEncode() {
        CompoundConcept.Clause c = CompoundConcept.decode("has function" + CompoundConcept.SEP + EX + "Suppression");
        assertEquals("has function", c.relation());
        assertEquals(EX + "Suppression", c.conceptIri());
        assertEquals("has function" + CompoundConcept.SEP + EX + "Suppression",
                CompoundConcept.encode("has function", EX + "Suppression"));
        // Bare IRI (no relation) stays bare - backward compatible with flat alignments.
        assertEquals(EX + "Drone", CompoundConcept.encode(null, EX + "Drone"));
        assertEquals(null, CompoundConcept.decode(EX + "Drone").relation());
    }
}
