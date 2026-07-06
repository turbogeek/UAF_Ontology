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
    // Deliberately generic (owner decision): the profile applies to ANY UML-based model
    // (UML, SysML 1.x, UAF, ...), so neither the name nor the metaclass is UAF-specific.
    public static final String PROFILE_NAME = "Semantic Alignment Profile";
    public static final String STEREOTYPE_NAME = "SemanticAlignment";
    public static final String PROPERTY_NAME = "mappedConceptURI";

    // Location of the SHIPPED profile module (deployed under <plugin>/profiles);
    // set once by the plugin at init. The profile is a real .mdzip, never hand-built.
    private static volatile java.io.File shippedProfile;

    public static void setShippedProfile(java.io.File file) {
        shippedProfile = file;
    }

    /**
     * Makes the shipped profile available by mounting it as a used module when the
     * stereotype is not yet resolvable. MUST be called OUTSIDE any model session
     * (module mounting is a project-structure operation).
     *
     * useModule can raise a modal error dialog on failure, which would freeze the EDT
     * if we were the ones pumping it; to stay non-blocking the mount runs via
     * invokeLater and any error dialog it spawns is auto-dismissed by a short-lived
     * window watcher. Callers get a definite yes/no by re-checking the stereotype.
     *
     * @return true when the stereotype is resolvable afterwards
     */
    public static boolean ensureProfileAvailable(Project project) {
        if (StereotypesHelper.getStereotype(project, STEREOTYPE_NAME) != null) {
            return true;
        }
        java.io.File file = shippedProfile;
        if (file == null || !file.exists()) {
            log.error("Shipped profile module not found: " + file);
            return false;
        }
        // Preferred path: mount the shipped module. It is silent WHEN it resolves; a
        // programmatically-exported module can reference its origin project's UML
        // Standard Profile and fail portability ("Module not found") - the watcher
        // dismisses that error popup so we can fall back rather than hang the EDT.
        java.util.Set<java.awt.Window> baseline = new java.util.HashSet<>(
                java.util.Arrays.asList(java.awt.Window.getWindows()));
        javax.swing.Timer watcher = new javax.swing.Timer(150, null);
        watcher.addActionListener(e -> dismissNewDialogs(baseline));
        watcher.start();
        Runnable mount = () -> {
            try {
                com.nomagic.magicdraw.core.project.ProjectDescriptor descriptor =
                        com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
                                .createProjectDescriptor(file.toURI());
                boolean used = com.nomagic.magicdraw.core.Application.getInstance()
                        .getProjectsManager().useModule(project, descriptor);
                log.info("Mounted Semantic Alignment Profile module: " + used);
            } catch (Throwable t) {
                log.error("Shipped profile module did not mount (portability issue)", t);
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                mount.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(mount);
            }
        } catch (Exception e) {
            log.error("Profile mount dispatch failed", e);
        } finally {
            watcher.stop();
        }
        if (StereotypesHelper.getStereotype(project, STEREOTYPE_NAME) != null) {
            return true;
        }

        // Fallback: instantiate the profile from its shipped definition so the tool never
        // breaks. This is NOT hand-authoring by the user - it materializes the SAME
        // generic profile the plugin ships (SemanticAlignment on the UML Element metaclass
        // with the three tags), resolving against THIS project's own UML metamodel. Logged
        // prominently so the portable-module path can replace it later.
        log.warn("Falling back to in-project instantiation of the shipped Semantic Alignment Profile "
                + "definition (portable module mount unavailable).");
        instantiateShippedProfile(project);
        return StereotypesHelper.getStereotype(project, STEREOTYPE_NAME) != null;
    }

    /** Disposes any dialog that appeared after the baseline (e.g. a mount error popup). */
    private static void dismissNewDialogs(java.util.Set<java.awt.Window> baseline) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof java.awt.Dialog dialog && dialog.isVisible()
                    && !baseline.contains(window)) {
                log.warn("Auto-dismissing dialog during profile mount: " + dialog.getTitle());
                dialog.setVisible(false);
                dialog.dispose();
            }
        }
    }

    /**
     * Materializes the shipped generic profile (SemanticAlignment on UML Element +
     * mappedConceptURI/ontologySource/mappingConfidence tags) inside the project, in one
     * session. Definition is fixed and generic - identical to the shipped .mdzip content.
     */
    private static void instantiateShippedProfile(Project project) {
        Runnable build = () -> {
            com.nomagic.magicdraw.openapi.uml.SessionManager sm =
                    com.nomagic.magicdraw.openapi.uml.SessionManager.getInstance();
            sm.createSession(project, "Include Semantic Alignment Profile");
            try {
                com.nomagic.uml2.impl.ElementsFactory ef = project.getElementsFactory();
                com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile profile = ef.createProfileInstance();
                profile.setName(PROFILE_NAME);
                profile.setOwner(project.getPrimaryModel());
                com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class elementMeta =
                        StereotypesHelper.getAllMetaClasses(project).stream()
                                .filter(c -> "Element".equals(c.getName())).findFirst().orElse(null);
                if (elementMeta == null) {
                    throw new IllegalStateException("UML metaclass Element not found");
                }
                Stereotype stereotype = StereotypesHelper.createStereotype(
                        project, STEREOTYPE_NAME, java.util.List.of(elementMeta));
                stereotype.setOwner(profile);
                for (String tag : new String[]{PROPERTY_NAME, "ontologySource", "mappingConfidence"}) {
                    com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property prop =
                            ef.createPropertyInstance();
                    prop.setName(tag);
                    stereotype.getOwnedAttribute().add(prop);
                }
                sm.closeSession(project);
            } catch (Throwable t) {
                try {
                    sm.cancelSession(project);
                } catch (Throwable ignored) {
                    // session already gone
                }
                log.error("In-project profile instantiation failed", t);
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                build.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(build);
            }
        } catch (Exception e) {
            log.error("Profile instantiation dispatch failed", e);
        }
    }

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
            log.error("Stereotype '" + STEREOTYPE_NAME + "' not found. Call ensureProfileAvailable "
                    + "(outside the session) so the shipped profile module gets mounted.");
            throw new IllegalStateException("Semantic Alignment Profile must be mounted in this project.");
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
