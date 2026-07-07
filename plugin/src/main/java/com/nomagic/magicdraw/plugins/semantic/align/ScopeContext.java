package com.nomagic.magicdraw.plugins.semantic.align;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MODELING CONTEXT around a selected element, used to make suggestions scope-aware so the
 * same word resolves differently depending on WHERE it sits in the model. Three orthogonal
 * signals (owner requirement, 2026-07-07):
 *
 * <ol>
 *   <li><b>uafLayer</b> - the UAF architecture layer (Operational / Service / Resource / ...).
 *       It shifts the search toward the right abstraction: an Operational element leans logical
 *       (uaf operational namespace), a Resource element leans physical (cco / sumo / qudt).</li>
 *   <li><b>constructKind</b> - the metaclass CATEGORY (STRUCTURE / BEHAVIOR / CONNECTOR / VALUE).
 *       A SysML {@code Activity} (BEHAVIOR) should prefer a BFO occurrent (a process / "Act of
 *       ..."); a {@code Block} / {@code Part} (STRUCTURE) should prefer a BFO continuant (an
 *       object). {@link ConceptCategoryIndex} supplies each concept's BFO category.</li>
 *   <li><b>contextTerms</b> - the surrounding STRUCTURE as weighted terms: the owner's name/type
 *       ("SUV" / "Sport Utility Vehicle"), the element's own type ("V8 Engine"), sibling part
 *       names. A part {@code engine} owned by an SUV should resolve to a vehicle engine, not a
 *       pump or tractor engine. Context terms disambiguate by boosting concepts whose
 *       label / alt-labels / comment overlap them.</li>
 * </ol>
 *
 * All fields are optional; an empty ScopeContext ({@link #EMPTY}) makes the ranker behave exactly
 * as the non-scoped path. This class is Cameo-free: the plugin populates it from the model and
 * hands it to the service, which consumes it. {@link #deriveConstructKind(String)} is the pure
 * metaclass-to-kind bridge the plugin uses, unit-testable without Cameo.
 * Trace: design/use_cases.md UC-2.8 (scope-aware context search)
 */
public record ScopeContext(String uafLayer, String constructKind, List<ContextTerm> contextTerms) {

    /** A surrounding-context term and how strongly it should bias disambiguation. */
    public record ContextTerm(String text, Role role) {
        public double weight() {
            return role == null ? Role.SIBLING.weight : role.weight;
        }
    }

    /** Where a context term came from, and its disambiguation strength (0..1). */
    public enum Role {
        /** The element's own declared type (e.g. part {@code engine} typed {@code V8 Engine}). */
        TYPE(1.0),
        /** The owning element's name or type (e.g. the SUV that owns the engine). */
        OWNER(0.8),
        /** A sibling feature's name or type (e.g. {@code transmission}, {@code chassis}). */
        SIBLING(0.5);

        private final double weight;

        Role(double weight) {
            this.weight = weight;
        }
    }

    /** Construct categories, aligned to BFO's occurrent/continuant split where it applies. */
    public static final String STRUCTURE = "STRUCTURE"; // Class, Block, Part, Property -> continuant
    public static final String BEHAVIOR = "BEHAVIOR";   // Activity, Action, State, Interaction -> occurrent
    public static final String CONNECTOR = "CONNECTOR"; // Association, Connector, Port
    public static final String VALUE = "VALUE";         // ValueType, quantity -> quality / QUDT

    public static final ScopeContext EMPTY = new ScopeContext(null, null, List.of());

    public ScopeContext {
        contextTerms = contextTerms == null ? List.of() : List.copyOf(contextTerms);
    }

    public boolean isEmpty() {
        return (uafLayer == null || uafLayer.isBlank())
                && (constructKind == null || constructKind.isBlank())
                && contextTerms.isEmpty();
    }

    /** Normalized upper-case layer token (e.g. "RESOURCE"), or null. */
    public String layerKey() {
        return uafLayer == null || uafLayer.isBlank() ? null : uafLayer.trim().toUpperCase(Locale.ROOT);
    }

    /** Normalized upper-case construct kind (e.g. "BEHAVIOR"), or null. */
    public String kindKey() {
        return constructKind == null || constructKind.isBlank()
                ? null : constructKind.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Pure metaclass -> construct-kind bridge for the plugin (no Cameo types referenced, so it is
     * unit-testable off-line). Accepts a UML/SysML/KerML metaclass or stereotype base name and
     * returns one of {@link #STRUCTURE} / {@link #BEHAVIOR} / {@link #CONNECTOR} / {@link #VALUE},
     * or null when it cannot be classified (then no category bias is applied).
     */
    public static String deriveConstructKind(String metaclassOrBase) {
        if (metaclassOrBase == null || metaclassOrBase.isBlank()) {
            return null;
        }
        String m = metaclassOrBase.trim().toLowerCase(Locale.ROOT);
        // Behaviors / occurrents: activities, actions, states, interactions, use cases, functions.
        if (contains(m, "activity", "action", "behavior", "behaviour", "interaction", "state",
                "transition", "usecase", "use case", "function", "operation", "message",
                "event", "process", "step", "calculation")) {
            return BEHAVIOR;
        }
        // Connectors: relationships that link two ends.
        if (contains(m, "association", "connector", "dependency", "port", "flow", "itemflow",
                "interface", "proxyport", "fullport", "link")) {
            return CONNECTOR;
        }
        // Values / qualities: value types, quantities, units, primitive-valued properties.
        if (contains(m, "valuetype", "value type", "quantity", "unit", "measure", "datatype",
                "data type", "enumeration", "literal", "constraint")) {
            return VALUE;
        }
        // Structures / continuants: everything object-like (default for the common cases).
        if (contains(m, "class", "block", "part", "property", "component", "node", "instance",
                "object", "artifact", "resource", "performer", "actor", "person", "organization",
                "system", "subsystem", "item", "structure", "definition", "usage")) {
            return STRUCTURE;
        }
        return null;
    }

    /**
     * Pure UAF-layer derivation from the applied stereotype names and the owning-package names
     * (no Cameo types referenced - unit-testable). The stereotype names win over the package
     * names (a {@code ResourcePerformer} in an "Operational" package is a Resource element).
     * Returns a {@link LayerRouter} key (RESOURCE / OPERATIONAL / …) or null when nothing matches.
     */
    public static String deriveLayer(List<String> stereotypeNames, List<String> ownerNames) {
        String fromStereotype = scanLayer(stereotypeNames);
        return fromStereotype != null ? fromStereotype : scanLayer(ownerNames);
    }

    private static String scanLayer(List<String> names) {
        if (names == null) {
            return null;
        }
        for (String n : names) {
            if (n == null) {
                continue;
            }
            String s = n.toLowerCase(Locale.ROOT);
            // Order: most specific layer keywords first; "operational"/"service"/"resource" are
            // the UAF grid rows, then the motivation/actual/security/project/parameter layers.
            if (s.contains("operational")) {
                return "OPERATIONAL";
            }
            if (s.contains("service")) {
                return "SERVICE";
            }
            if (s.contains("resource")) {
                return "RESOURCE";
            }
            if (s.contains("personnel")) {
                return "PERSONNEL";
            }
            if (s.contains("security")) {
                return "SECURITY";
            }
            if (s.contains("project")) {
                return "PROJECT";
            }
            if (s.contains("parameter") || s.contains("measurement")) {
                return "PARAMETERS";
            }
            if (s.contains("strategic") || s.contains("capability")) {
                return "STRATEGIC";
            }
            if (s.contains("actual") || s.contains("organization") || s.contains("organisation")) {
                return "PERSONNEL";
            }
        }
        return null;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Convenience builder used by tests and the plugin. */
    public static ScopeContext of(String uafLayer, String constructKind, List<ContextTerm> terms) {
        return new ScopeContext(uafLayer, constructKind, terms == null ? List.of() : terms);
    }

    /** Adds a context term, returning a new immutable ScopeContext (records are immutable). */
    public ScopeContext withTerm(String text, Role role) {
        if (text == null || text.isBlank()) {
            return this;
        }
        List<ContextTerm> next = new ArrayList<>(contextTerms);
        next.add(new ContextTerm(text.trim(), role));
        return new ScopeContext(uafLayer, constructKind, next);
    }
}
