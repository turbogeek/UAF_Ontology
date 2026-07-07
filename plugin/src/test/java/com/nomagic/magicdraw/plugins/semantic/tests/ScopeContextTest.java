package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.ScopeContext;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Validates the pure metaclass -> construct-kind bridge the plugin uses to build a
 * {@link ScopeContext} from a selected element WITHOUT referencing any Cameo type, so the
 * mapping is unit-testable off-line. A SysML {@code Activity} must classify as BEHAVIOR (so
 * suggestions lean toward BFO occurrents), a {@code Block}/{@code Part} as STRUCTURE, an
 * {@code Association} as CONNECTOR, a {@code ValueType} as VALUE.
 * Trace: design/use_cases.md UC-2.8
 */
public class ScopeContextTest {

    @Test
    public void testBehaviorMetaclasses() {
        assertEquals(ScopeContext.BEHAVIOR, ScopeContext.deriveConstructKind("Activity"));
        assertEquals(ScopeContext.BEHAVIOR, ScopeContext.deriveConstructKind("CallBehaviorAction"));
        assertEquals(ScopeContext.BEHAVIOR, ScopeContext.deriveConstructKind("StateMachine"));
        assertEquals(ScopeContext.BEHAVIOR, ScopeContext.deriveConstructKind("Use Case"));
        assertEquals(ScopeContext.BEHAVIOR, ScopeContext.deriveConstructKind("Operation"));
    }

    @Test
    public void testStructureMetaclasses() {
        assertEquals(ScopeContext.STRUCTURE, ScopeContext.deriveConstructKind("Class"));
        assertEquals(ScopeContext.STRUCTURE, ScopeContext.deriveConstructKind("Block"));
        assertEquals(ScopeContext.STRUCTURE, ScopeContext.deriveConstructKind("PartProperty"));
        assertEquals(ScopeContext.STRUCTURE, ScopeContext.deriveConstructKind("ResourcePerformer"));
    }

    @Test
    public void testConnectorAndValueMetaclasses() {
        assertEquals(ScopeContext.CONNECTOR, ScopeContext.deriveConstructKind("Association"));
        assertEquals(ScopeContext.CONNECTOR, ScopeContext.deriveConstructKind("ProxyPort"));
        assertEquals(ScopeContext.VALUE, ScopeContext.deriveConstructKind("ValueType"));
        assertEquals(ScopeContext.VALUE, ScopeContext.deriveConstructKind("Enumeration"));
    }

    @Test
    public void testUnclassifiableReturnsNull() {
        assertNull(ScopeContext.deriveConstructKind(null));
        assertNull(ScopeContext.deriveConstructKind(""));
        assertNull(ScopeContext.deriveConstructKind("Xyzzy"));
    }

    @Test
    public void testDeriveLayerFromStereotypeNames() {
        assertEquals("RESOURCE", ScopeContext.deriveLayer(List.of("ResourcePerformer"), List.of()));
        assertEquals("OPERATIONAL", ScopeContext.deriveLayer(List.of("OperationalActivity"), List.of()));
        assertEquals("SERVICE", ScopeContext.deriveLayer(List.of("ServiceInterface"), List.of()));
        assertEquals("STRATEGIC", ScopeContext.deriveLayer(List.of("Capability"), List.of()));
        assertEquals("PERSONNEL", ScopeContext.deriveLayer(List.of("ActualOrganization"), List.of()));
        assertNull(ScopeContext.deriveLayer(List.of("Foo", "Bar"), List.of()));
    }

    @Test
    public void testDeriveLayerStereotypeBeatsOwnerPackage() {
        // A ResourcePerformer sitting in an "Operational" package is still a Resource element.
        assertEquals("RESOURCE",
                ScopeContext.deriveLayer(List.of("ResourcePerformer"), List.of("Operational")));
    }

    @Test
    public void testDeriveLayerFallsBackToOwnerPackage() {
        assertEquals("SERVICE", ScopeContext.deriveLayer(List.of(), List.of("Services", "Root")));
        assertNull(ScopeContext.deriveLayer(List.of(), List.of()));
    }

    @Test
    public void testEmptyContextIsEmptyAndTermRolesWeightOrdered() {
        assertTrue(ScopeContext.EMPTY.isEmpty());
        assertTrue(new ScopeContext(null, null, List.of()).isEmpty());
        // Type context must bias harder than owner, which biases harder than a sibling.
        double type = new ScopeContext.ContextTerm("x", ScopeContext.Role.TYPE).weight();
        double owner = new ScopeContext.ContextTerm("x", ScopeContext.Role.OWNER).weight();
        double sibling = new ScopeContext.ContextTerm("x", ScopeContext.Role.SIBLING).weight();
        assertTrue("type > owner", type > owner);
        assertTrue("owner > sibling", owner > sibling);
    }

    @Test
    public void testWithTermIsImmutableAndAdds() {
        ScopeContext base = new ScopeContext("RESOURCE", "STRUCTURE", List.of());
        ScopeContext with = base.withTerm("Sport Utility Vehicle", ScopeContext.Role.OWNER);
        assertTrue("original unchanged", base.contextTerms().isEmpty());
        assertEquals(1, with.contextTerms().size());
        assertEquals("RESOURCE", with.layerKey());
        assertEquals("STRUCTURE", with.kindKey());
    }
}
