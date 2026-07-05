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
     */
    public String generatePlainSBVR(String elementURI, String conceptFQN, String relation, String targetName) {
        String baseName = getLocalName(elementURI);
        String conceptName = getLocalName(conceptFQN);

        if (relation == null || relation.trim().isEmpty()) {
            return "Instance: " + baseName + " is a " + conceptName + ".";
        } else if (targetName != null) {
            if (relation.equalsIgnoreCase("contains")) {
                return "Instance: " + baseName + " contains " + getLocalName(targetName) + ".";
            } else {
                return "Instance: " + baseName + " is a " + conceptName + " " + relation + " " + getLocalName(targetName) + ".";
            }
        }
        return "";
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
        // Regex splitting camelCase words (but keeps numbers attached to their predecessor word)
        result = result.replaceAll("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])", " ");
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
