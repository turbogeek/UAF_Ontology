package com.nomagic.magicdraw.plugins.semantic.align.terms;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Curated notification list of restrictively-licensed ontologies (owner policy: NOTIFY
 * the user, do not enforce). OLS4 does not reliably expose per-ontology license text, so
 * we key off the ontology prefix. Remote reference of any term is fine; the note exists
 * to warn the user before an action would STORE or redistribute a restricted term.
 * Editable at runtime via a licenses.properties override in the plugin dir.
 * Trace: OLS4 integration recommendation (owner loading & licensing policy)
 */
public final class OntologyLicenses {

    private static final Map<String, String> RESTRICTIVE = new LinkedHashMap<>();
    static {
        RESTRICTIVE.put("snomed", "SNOMED CT is restrictively licensed - a valid SNOMED "
                + "affiliate license is required to use or store these terms.");
        RESTRICTIVE.put("snomedct", "SNOMED CT is restrictively licensed - affiliate license required.");
        RESTRICTIVE.put("meddra", "MedDRA requires a subscription license.");
        RESTRICTIVE.put("loinc", "LOINC is subject to the Regenstrief LOINC license terms.");
        RESTRICTIVE.put("icd10", "ICD is subject to WHO license terms.");
        RESTRICTIVE.put("icd11", "ICD is subject to WHO license terms.");
        RESTRICTIVE.put("icd10cm", "ICD-10-CM is subject to license terms.");
        RESTRICTIVE.put("gmdn", "GMDN requires membership/license.");
        RESTRICTIVE.put("mesh", "MeSH carries NLM terms of use; verify redistribution rights.");
    }

    // Private constructor to prevent instantiation of utility class
    private OntologyLicenses() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** License note for an ontology prefix, or null when unrestricted / unknown. */
    public static String noteFor(String ontologyPrefix) {
        if (ontologyPrefix == null) {
            return null;
        }
        return RESTRICTIVE.get(ontologyPrefix.toLowerCase(Locale.ROOT));
    }

    public static boolean isRestrictive(String ontologyPrefix) {
        return noteFor(ontologyPrefix) != null;
    }

    /** Allows a plugin-dir override to add site-specific restricted prefixes. */
    public static void register(String prefix, String note) {
        if (prefix != null && note != null) {
            RESTRICTIVE.put(prefix.toLowerCase(Locale.ROOT), note);
        }
    }
}
