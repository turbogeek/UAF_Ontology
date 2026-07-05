package com.nomagic.magicdraw.plugins.semantic.reasoning;

import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry + selector for reasoner engines. New engines register here; the active one
 * is chosen with -Dsemantic.plugin.reasoner=<id> (default jena-rules) so benchmarking
 * an alternative is a JVM property, not a code change.
 * Trace: v3 plan section 4 (non-denominational reasoning)
 */
public final class Reasoners {

    private static final String PROPERTY = "semantic.plugin.reasoner";
    private static final Map<String, ReasonerAdapter> REGISTRY = new LinkedHashMap<>();

    static {
        register(new JenaRulesReasonerAdapter());
        // Candidates to benchmark (owner: stay non-denominational until proven):
        //   - ELK 0.6 via OWLAPI 5.5 (EL profile; fast classification)
        //   - HermiT (full DL; heavyweight)
        //   - Openllet (full DL; gUFO-rich axioms)
    }

    // Private constructor to prevent instantiation of utility class
    private Reasoners() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static synchronized void register(ReasonerAdapter adapter) {
        REGISTRY.put(adapter.id(), adapter);
    }

    /** The selected engine; falls back loudly to the default when the id is unknown. */
    public static ReasonerAdapter active() {
        String requested = System.getProperty(PROPERTY, JenaRulesReasonerAdapter.ID);
        ReasonerAdapter adapter = REGISTRY.get(requested);
        if (adapter == null) {
            DiagnosticLog.event("WARN", "Unknown reasoner '" + requested
                    + "' - falling back to " + JenaRulesReasonerAdapter.ID
                    + " (registered: " + REGISTRY.keySet() + ")");
            adapter = REGISTRY.get(JenaRulesReasonerAdapter.ID);
        }
        return adapter;
    }
}
