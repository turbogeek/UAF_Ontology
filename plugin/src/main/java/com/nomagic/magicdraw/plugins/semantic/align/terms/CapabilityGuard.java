package com.nomagic.magicdraw.plugins.semantic.align.terms;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces the capability-vs-process distinction (owner: "Search is a Capability, but
 * NCIT Search is an Activity"). A UAF Capability aligns to a realizable/disposition
 * concept in the capability slot and is RELATED to the activity it realizes in a separate
 * slot - never mapped directly to the activity term.
 *
 * This guard reads a candidate term's Semantic_Type (from an OLS4 lookup) and warns when
 * a process/activity term is being placed in the capability slot. It is advisory (a UI
 * flag), consistent with the notify-don't-block posture.
 * Trace: OLS4 integration recommendation section 4
 */
public final class CapabilityGuard {

    /** Which slot a candidate is being considered for. */
    public enum Slot {
        /** The capability/disposition itself (realizable entity). */
        CAPABILITY,
        /** The process/activity the capability realizes (occurrent). */
        REALIZED_ACTIVITY,
        /** No capability semantics - ordinary alignment. */
        GENERIC
    }

    // Semantic types (and gUFO/BFO leaf names) that denote a process/activity/event.
    private static final Set<String> ACTIVITY_TYPES = Set.of(
            "activity", "process", "event", "act", "plannedact", "planned act", "procedure",
            "occurrent", "healthcareactivity");

    // Semantic types that denote a realizable/disposition/capability.
    private static final Set<String> REALIZABLE_TYPES = Set.of(
            "capability", "disposition", "function", "role", "realizableentity",
            "realizable entity", "intrinsicmode", "aspect");

    // Private constructor to prevent instantiation of utility class
    private CapabilityGuard() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * @return a warning to show the user, or empty when the pairing is coherent
     */
    public static Optional<String> validate(Slot slot, String semanticType) {
        if (slot == Slot.GENERIC || semanticType == null || semanticType.isBlank()) {
            return Optional.empty();
        }
        String type = semanticType.toLowerCase(Locale.ROOT).trim();
        if (slot == Slot.CAPABILITY && isActivity(type)) {
            return Optional.of("This term is a " + semanticType + " (a process/activity), but a "
                    + "Capability is the ABILITY to perform it. Put this in the 'realized activity' "
                    + "slot and pick a capability/disposition concept for the capability slot.");
        }
        if (slot == Slot.REALIZED_ACTIVITY && isRealizable(type)) {
            return Optional.of("This term is a " + semanticType + " (a disposition/capability), not a "
                    + "process. The 'realized activity' slot expects a process/activity term.");
        }
        return Optional.empty();
    }

    public static boolean isActivity(String semanticType) {
        return semanticType != null
                && ACTIVITY_TYPES.contains(semanticType.toLowerCase(Locale.ROOT).trim());
    }

    public static boolean isRealizable(String semanticType) {
        return semanticType != null
                && REALIZABLE_TYPES.contains(semanticType.toLowerCase(Locale.ROOT).trim());
    }
}
