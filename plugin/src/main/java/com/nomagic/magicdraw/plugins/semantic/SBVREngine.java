package com.nomagic.magicdraw.plugins.semantic;

/**
 * Generates HTML-formatted SBVR (Semantics of Business Vocabulary and Business Rules) Structured English markup.
 * Integrates elements, stereotypes, and target ontology bindings.
 * Trace: PLG-REQ-04
 */
public class SBVREngine {

    /**
     * Generates a fully formatted SBVR Structured English string wrapped in HTML style tokens.
     *
     * @param elementURI  The local or absolute URI of the source model element.
     * @param conceptFQN  The fully-qualified name or prefix IRI of the aligned ontology concept.
     * @param relation    Optional name of the relation (e.g. "contains", "owned by").
     * @param targetName  Optional name of the target node in the relationship.
     * @return HTML-styled SBVR Structured English string.
     */
    public String generateSBVR(String elementURI, String conceptFQN, String relation, String targetName) {
        String sbvrText = generatePlainSBVR(elementURI, conceptFQN, relation, targetName);
        return "<html><body><span style='font-family: \"Courier New\", monospace; font-size: 12px; color: #cbd5e1;'>"
                + sbvrText + "</span></body></html>";
    }

    /**
     * Translates element parameters into a plain-text SBVR Structured English sentence.
     * Covers scenarios SC-01 through SC-08 of design spec section 10.1:
     * instantiation, composition, association, ownership, generalization, refinement,
     * conformance, and goal channeling.
     */
    public String generatePlainSBVR(String elementURI, String conceptFQN, String relation, String targetName) {
        String baseName = getLocalName(elementURI);
        String conceptName = getLocalName(conceptFQN);
        String rel = relation == null ? "" : relation.trim();

        if (rel.isEmpty()) {
            // SC-01: plain instantiation
            return "Instance: " + baseName + " is a " + conceptName + ".";
        }
        if (rel.equalsIgnoreCase("specializes") || rel.equalsIgnoreCase("is a kind of")) {
            // SC-05: generalizations describe concepts, not instances
            return "Concept: " + baseName + " is a kind of " + conceptName + ".";
        }
        if (targetName == null || targetName.trim().isEmpty()) {
            // SC-06: the aligned concept itself is the relation target ("X refines Y")
            return "Instance: " + baseName + " " + rel + " " + conceptName + ".";
        }
        if (rel.equalsIgnoreCase("contains")) {
            // SC-02: composition
            return "Instance: " + baseName + " contains " + getLocalName(targetName) + ".";
        }
        if (equalsIgnoringSpacing(conceptName, rel)) {
            // SC-03/SC-07: the aligned concept IS the relation (e.g. sr:connectedTo with
            // relation "connected to") - repeating it as a type clause would be circular
            return "Instance: " + baseName + " " + rel + " " + getLocalName(targetName) + ".";
        }
        if (baseName.equals(conceptName) || baseName.endsWith(" " + conceptName)) {
            // SC-08: suppress the type clause when the name already states the concept
            // ("Prevent Spoilage Goal is a Goal" reads as redundant to SBVR reviewers)
            return "Instance: " + baseName + " " + rel + " " + getLocalName(targetName) + ".";
        }
        // SC-04: full form with type clause and relation
        return "Instance: " + baseName + " is a " + conceptName + " " + rel + " " + getLocalName(targetName) + ".";
    }

    /**
     * Composes a COMPOUND concept as one SBVR Structured English sentence: a genus ("is a kind
     * of") plus differentia clauses, each a (relation phrase, qualifier concept) pair. This is
     * what lets a modeler say WHAT an element is when no single ontology concept captures it -
     * e.g. genus {@code Drone} + clauses {@code [(suppresses, Mosquito), (uses, Chemical Sprayer)]}
     * reads: "A Mosquito Suppression Drone is a Drone that suppresses a Mosquito and uses a
     * Chemical Sprayer." Owner request (2026-07-07): compound concepts that read correctly in SBVR.
     *
     * @param elementLabel the new concept's name (usually the element name)
     * @param genusIriOrName the genus concept (IRI or name); the "is a kind of" base
     * @param clauses ordered (relationPhrase, qualifierIriOrName) pairs; relationPhrase is a verb
     *               phrase ("suppresses", "has function", "is part of")
     * @return a single "A X is a Genus that r1 a Q1 and r2 a Q2." sentence
     */
    public String generateCompoundSBVR(String elementLabel, String genusIriOrName,
                                       java.util.List<String[]> clauses) {
        String subject = getLocalName(elementLabel);
        String genus = getLocalName(genusIriOrName);
        StringBuilder sb = new StringBuilder("Concept: ");
        sb.append(article(subject)).append(' ').append(subject);
        boolean genusStated = false;
        // Always state the genus for a DEFINITION (it anchors the differentia) - suppress only
        // when the name IS exactly the genus. Unlike a flat instance sentence we must not drop
        // "is a Drone" just because the name ends in "Drone", or the differentia lose their verb.
        if (genus != null && !genus.isBlank() && !subject.equalsIgnoreCase(genus)) {
            sb.append(" is ").append(article(genus).toLowerCase(java.util.Locale.ROOT)).append(' ').append(genus);
            genusStated = true;
        }
        boolean first = true;
        if (clauses != null) {
            for (String[] clause : clauses) {
                if (clause == null || clause.length < 2) {
                    continue;
                }
                String rel = clause[0] == null ? "" : clause[0].trim();
                String qualifier = getLocalName(clause[1]);
                if (qualifier.isBlank()) {
                    continue;
                }
                // "that" introduces the first differentia; "and" chains the rest. When no genus
                // was stated we still need a leading verb, so the first connector is bare.
                String connector = first ? (genusStated ? " that " : " ") : " and ";
                sb.append(connector);
                // A verb is mandatory for readable SBVR; a blank relation means "is a <type>".
                sb.append(rel.isEmpty() ? "is " : rel + " ");
                sb.append(article(qualifier).toLowerCase(java.util.Locale.ROOT)).append(' ').append(qualifier);
                first = false;
            }
        }
        return sb.append('.').toString();
    }

    /** "A"/"An" for a display label, by leading vowel sound (simple heuristic). */
    private String article(String name) {
        if (name == null || name.isBlank()) {
            return "A";
        }
        char c = Character.toLowerCase(name.charAt(0));
        return ("aeiou".indexOf(c) >= 0) ? "An" : "A";
    }

    /**
     * Relation labels arrive as prose ("connected to") while concept local names arrive
     * camelCase-split ("Connected To"); spacing and case must not affect the comparison.
     */
    private boolean equalsIgnoringSpacing(String left, String right) {
        return left.replace(" ", "").equalsIgnoreCase(right.replace(" ", ""));
    }

    /**
     * Strips namespace prefixes and returns the local identifier name.
     */
    public String getLocalName(String uriOrFqn) {
        if (uriOrFqn == null) {
            return "";
        }
        int hashIdx = uriOrFqn.lastIndexOf('#');
        if (hashIdx != -1) {
            return formatLabel(uriOrFqn.substring(hashIdx + 1));
        }
        int colonIdx = uriOrFqn.lastIndexOf(':');
        if (colonIdx != -1) {
            return formatLabel(uriOrFqn.substring(colonIdx + 1));
        }
        int slashIdx = uriOrFqn.lastIndexOf('/');
        if (slashIdx != -1) {
            return formatLabel(uriOrFqn.substring(slashIdx + 1));
        }
        return formatLabel(uriOrFqn);
    }

    /**
     * Formats camelCase or snake_case identifiers into capitalized, space-separated words.
     */
    private String formatLabel(String rawName) {
        String result = rawName.replace("inst-", "").replace("post-", "").replace("org-", "");
        // Split camelCase on lower/digit-to-upper and acronym-to-word boundaries only;
        // hyphenated acronyms like "AT-AT" must stay intact.
        result = result.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ");
        result = result.replace("_", " ");
        
        String[] words = result.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }
}
