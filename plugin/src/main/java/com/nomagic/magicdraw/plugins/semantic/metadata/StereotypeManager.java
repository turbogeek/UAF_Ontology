package com.nomagic.magicdraw.plugins.semantic.metadata;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import org.apache.log4j.Logger;

/**
 * Handles applying custom stereotypes and editing Tagged Values inside Cameo.
 * Mapped concept IRIs are stored in the project's native file structure.
 * Trace: PLG-REQ-03, PLG-REQ-05
 */
public final class StereotypeManager {

    private static final Logger log = Logger.getLogger(StereotypeManager.class);
    public static final String PROFILE_NAME = "UAF Semantic Alignment Profile";
    public static final String STEREOTYPE_NAME = "SemanticAlignment";
    public static final String PROPERTY_NAME = "mappedConceptURI";

    // Private constructor to prevent instantiation of utility class
    private StereotypeManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Applies the SemanticAlignment stereotype to a UML/SysML element
     * and sets the mappedConceptURI property to the target IRI.
     *
     * @param element    The UML/SysML model element being aligned.
     * @param conceptURI The IRI representing the aligned concept in the ontology.
     */
    public static void applySemanticMapping(Element element, String conceptURI) {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null.");
        }
        if (conceptURI == null || conceptURI.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be empty.");
        }

        Project project = Project.getProject(element);
        if (project == null) {
            throw new IllegalStateException("Element is not attached to an active project.");
        }

        // Fetch the stereotype definition from the active profile
        Stereotype stereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME, PROFILE_NAME);
        
        if (stereotype == null) {
            // Attempt general stereotype search in case profile prefix varies
            stereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        }

        if (stereotype == null) {
            log.error("Stereotype '" + STEREOTYPE_NAME + "' not found. Make sure UAF Semantic Profile is mounted.");
            throw new IllegalStateException("Semantic Alignment Profile must be active in this project.");
        }

        // Apply stereotype if not already present
        if (!StereotypesHelper.hasStereotype(element, stereotype)) {
            StereotypesHelper.addStereotype(element, stereotype);
            log.debug("Applied stereotype '" + STEREOTYPE_NAME + "' to element: " + element.getHumanName());
        }

        // Set the tagged value property
        StereotypesHelper.setStereotypePropertyValue(element, stereotype, PROPERTY_NAME, conceptURI);
        log.info("Mapped element '" + element.getHumanName() + "' to concept URI: " + conceptURI);
    }
}
