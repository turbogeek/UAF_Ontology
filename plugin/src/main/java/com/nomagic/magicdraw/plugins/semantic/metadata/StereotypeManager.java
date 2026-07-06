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

    // Model-level stereotype: carries the derived ontology's root IRI + version and the
    // instrumentation provenance. Applied to the model/package root when a model is
    // instrumented (owner requirement 2026-07-06 - see design/use_cases.md UC-1.2).
    public static final String MODEL_STEREOTYPE_NAME = "SemanticModel";
    public static final String TAG_ROOT_IRI = "ontologyRootIRI";
    public static final String TAG_VERSION = "ontologyVersion";
    public static final String TAG_INSTRUMENTED_BY = "instrumentedBy";
    public static final String TAG_INSTRUMENTED_DATE = "instrumentedDate";

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
        // Mount the shipped module as a read-only USED module - exactly how UAF/SoaML ship
        // their profiles. useModule's boolean return is the AUTHORITATIVE success signal
        // (a stereotype re-query can lag module indexing). The .mdzip is a proper shared
        // module (ModulesService.shareOnTask), so on a healthy install this succeeds and the
        // profile is NEVER materialized inside the user's project. Any modal error dialog is
        // LOGGED (title + message) before being dismissed so a real failure is diagnosable,
        // not silently swallowed.
        java.util.Set<java.awt.Window> baseline = new java.util.HashSet<>(
                java.util.Arrays.asList(java.awt.Window.getWindows()));
        javax.swing.Timer watcher = new javax.swing.Timer(150, null);
        watcher.addActionListener(e -> logAndDismissNewDialogs(baseline));
        watcher.start();
        boolean[] used = {false};
        Runnable mount = () -> {
            try {
                com.nomagic.magicdraw.core.project.ProjectDescriptor descriptor =
                        com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
                                .createProjectDescriptor(file.toURI());
                used[0] = com.nomagic.magicdraw.core.Application.getInstance()
                        .getProjectsManager().useModule(project, descriptor);
                log.info("useModule(Semantic Alignment Profile) -> " + used[0] + " from " + file);
            } catch (Throwable t) {
                log.error("Shipped profile module did not mount", t);
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
        boolean resolved = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME) != null;
        if (used[0] || resolved) {
            if (used[0] && !resolved) {
                log.info("Semantic Alignment Profile mounted (useModule=true); stereotype resolution "
                        + "may lag one indexing pass.");
            }
            return true;
        }

        // Mount failed. We do NOT silently materialize the profile inside the user's project
        // (the owner's requirement: it must be a USED module like the other profiles, never
        // embedded). Surface a hard failure so the caller's error path runs. The in-project
        // author remains available ONLY as an explicit developer/CI opt-in.
        if (Boolean.getBoolean("semantic.plugin.allow.inproject.profile")) {
            log.warn("mount failed; -Dsemantic.plugin.allow.inproject.profile=true -> "
                    + "materializing the profile IN-PROJECT (developer/CI mode only).");
            instantiateShippedProfile(project);
            return StereotypesHelper.getStereotype(project, STEREOTYPE_NAME) != null;
        }
        log.error("Semantic Alignment Profile module did not mount (useModule=" + used[0]
                + ") from " + file + ". Not embedding the profile in the project; "
                + "check that the shared module is deployed to <install>/profiles.");
        return false;
    }

    /** Logs (title + message text) and disposes any dialog that appeared after the baseline. */
    private static void logAndDismissNewDialogs(java.util.Set<java.awt.Window> baseline) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof java.awt.Dialog dialog && dialog.isVisible()
                    && !baseline.contains(window)) {
                log.warn("Mount-time dialog captured then dismissed: title='" + dialog.getTitle()
                        + "' text='" + extractDialogText(dialog) + "'");
                dialog.setVisible(false);
                dialog.dispose();
            }
        }
    }

    /** Best-effort scrape of a dialog's visible text (labels) for diagnostics. */
    private static String extractDialogText(java.awt.Container container) {
        StringBuilder sb = new StringBuilder();
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JLabel label && label.getText() != null) {
                sb.append(label.getText()).append(' ');
            } else if (c instanceof java.awt.Container inner) {
                sb.append(extractDialogText(inner));
            }
        }
        return sb.toString().trim();
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
                // Make the stereotype INVISIBLE on diagrams and Specification-configured
                // via a «Customization» (the shipped-profile pattern: hideMetatype=true).
                createCustomization(project, ef, profile, stereotype);
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
     * Creates a «Customization» targeting the SemanticAlignment stereotype so that:
     * applying it never draws a «SemanticAlignment» label on diagrams (hideMetatype=true),
     * and the three tags surface as a "Semantic Alignment" group in the element's
     * Specification dialog. Mirrors the shipped-profile pattern learned from SysML/UAF
     * customizations (customizationTarget + hideMetatype + standardExpertConfiguration).
     * Must run inside the profile-building session.
     * Trace: owner requirement - invisible stereotype + Customization
     */
    private static void createCustomization(Project project,
            com.nomagic.uml2.impl.ElementsFactory ef,
            com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile profile,
            Stereotype target) {
        Stereotype customization = StereotypesHelper.getStereotype(project, "Customization");
        if (customization == null) {
            log.warn("«Customization» stereotype unavailable; SemanticAlignment label will not be auto-hidden.");
            return;
        }
        try {
            com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class custClass = ef.createClassInstance();
            custClass.setName("SemanticAlignment Customization");
            custClass.setOwner(profile);
            StereotypesHelper.addStereotype(custClass, customization);
            // Point the customization at our stereotype and hide its diagram label.
            StereotypesHelper.setStereotypePropertyValue(custClass, customization, "customizationTarget", target);
            StereotypesHelper.setStereotypePropertyValue(custClass, customization, "hideMetatype", Boolean.TRUE);
            StereotypesHelper.setStereotypePropertyValue(custClass, customization, "representationText", "Semantic Alignment");
            StereotypesHelper.setStereotypePropertyValue(custClass, customization, "category", "Semantic Alignment");
            // Surface exactly our three tags in the Specification (SPF = show property).
            java.util.List<String> spec = new java.util.ArrayList<>();
            for (String tag : new String[]{PROPERTY_NAME, "ontologySource", "mappingConfidence"}) {
                spec.add("<html><head><title>SPF</title></head><body><p>" + tag + "</p></body></html>");
            }
            StereotypesHelper.setStereotypePropertyValue(custClass, customization, "standardExpertConfiguration", spec);
            log.info("Created invisible Customization for " + STEREOTYPE_NAME);
        } catch (Throwable t) {
            log.error("Customization creation failed (stereotype still usable, just not auto-hidden)", t);
        }
    }

    /**
     * Applies the SemanticAlignment stereotype to a UML/SysML element
     * and sets the mappedConceptURI property to the target IRI.
     *
     * @param element    The UML/SysML model element being aligned.
     * @param conceptURI The IRI representing the aligned concept in the ontology.
     */
    public static void applySemanticMapping(Element element, String conceptURI) {
        if (conceptURI == null || conceptURI.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be empty.");
        }
        setSemanticConcepts(element, java.util.List.of(conceptURI));
    }

    /**
     * Applies the SemanticAlignment stereotype and stores an ORDERED, de-duplicated list of
     * mapped concept IRIs (index 0 is the pinned base concept - typically the UAF-stereotype
     * concept - and 1..n are additive narrowings). The mappedConceptURI tag is multi-valued;
     * passing a List sets all values at once. MUST run inside a write session.
     * Trace: design/use_cases.md UC-1.3, UC-2.2
     */
    public static void setSemanticConcepts(Element element, java.util.List<String> conceptURIs) {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null.");
        }
        // De-duplicate preserving first-occurrence order (base concept stays at index 0).
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        if (conceptURIs != null) {
            for (String c : conceptURIs) {
                if (c != null && !c.trim().isEmpty()) {
                    ordered.add(c.trim());
                }
            }
        }
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("At least one concept URI is required.");
        }
        Project project = Project.getProject(element);
        if (project == null) {
            throw new IllegalStateException("Element is not attached to an active project.");
        }
        Stereotype stereotype = resolveAlignmentStereotype(project);
        if (!StereotypesHelper.hasStereotype(element, stereotype)) {
            StereotypesHelper.addStereotype(element, stereotype);
            log.debug("Applied stereotype '" + STEREOTYPE_NAME + "' to element: " + element.getHumanName());
        }
        // A List value sets all values on the multi-valued tag at once (TagsHelper).
        StereotypesHelper.setStereotypePropertyValue(
                element, stereotype, PROPERTY_NAME, new java.util.ArrayList<>(ordered));
        log.info("Mapped element '" + element.getHumanName() + "' to concepts: " + ordered);
    }

    /** All mapped concept IRIs on the element (base first), or an empty list if unaligned. */
    public static java.util.List<String> getMappedConcepts(Element element) {
        if (element == null) {
            return java.util.List.of();
        }
        Project project = Project.getProject(element);
        if (project == null) {
            return java.util.List.of();
        }
        Stereotype stereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        if (stereotype == null || !StereotypesHelper.hasStereotype(element, stereotype)) {
            return java.util.List.of();
        }
        java.util.List<?> values = StereotypesHelper.getStereotypePropertyValue(
                element, stereotype, PROPERTY_NAME);
        if (values == null) {
            return java.util.List.of();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Object v : values) {
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /** Resolves the SemanticAlignment stereotype (profile-qualified first), or throws. */
    private static Stereotype resolveAlignmentStereotype(Project project) {
        Stereotype stereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME, PROFILE_NAME);
        if (stereotype == null) {
            stereotype = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        }
        if (stereotype == null) {
            log.error("Stereotype '" + STEREOTYPE_NAME + "' not found. Call ensureProfileAvailable "
                    + "(outside the session) so the shipped profile module gets mounted.");
            throw new IllegalStateException("Semantic Alignment Profile must be mounted in this project.");
        }
        return stereotype;
    }

    /** The model/package root that carries model-level instrumentation. */
    public static Element getModelRoot(Project project) {
        return project.getPrimaryModel();
    }

    /** True when this project's model root already carries the SemanticModel stereotype. */
    public static boolean isInstrumented(Project project) {
        if (project == null) {
            return false;
        }
        Stereotype modelStereo = StereotypesHelper.getStereotype(project, MODEL_STEREOTYPE_NAME);
        if (modelStereo == null) {
            return false;
        }
        Element root = getModelRoot(project);
        return root != null && StereotypesHelper.hasStereotype(root, modelStereo);
    }

    /** A stable default ontology root IRI derived from the project name. */
    public static String defaultRootIri(Project project) {
        String name = (project == null || project.getName() == null) ? "model" : project.getName();
        String slug = name.trim().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "model";
        }
        return "http://semantic.alignment/model/" + slug + "#";
    }

    /**
     * Applies the model-level SemanticModel stereotype to the project's model root with the
     * ontology root IRI + version + provenance. MUST run inside a write session (wrap with
     * TransactionWrapper.executeWrite); the profile must already be mounted (call
     * ensureProfileAvailable OUTSIDE the session first). Idempotent on the stereotype itself
     * (won't double-apply), but always refreshes the tag values.
     * Trace: design/use_cases.md UC-1.1, UC-1.2
     */
    public static void applyModelInstrumentation(Project project, String rootIri, String version,
            String instrumentedBy) {
        Stereotype modelStereo = StereotypesHelper.getStereotype(project, MODEL_STEREOTYPE_NAME);
        if (modelStereo == null) {
            throw new IllegalStateException("SemanticModel stereotype not found - mount the "
                    + "Semantic Alignment Profile first (ensureProfileAvailable).");
        }
        Element root = getModelRoot(project);
        if (root == null) {
            throw new IllegalStateException("Project has no model root to instrument.");
        }
        if (!StereotypesHelper.hasStereotype(root, modelStereo)) {
            StereotypesHelper.addStereotype(root, modelStereo);
        }
        StereotypesHelper.setStereotypePropertyValue(root, modelStereo, TAG_ROOT_IRI, rootIri);
        StereotypesHelper.setStereotypePropertyValue(root, modelStereo, TAG_VERSION, version);
        StereotypesHelper.setStereotypePropertyValue(root, modelStereo, TAG_INSTRUMENTED_BY, instrumentedBy);
        StereotypesHelper.setStereotypePropertyValue(root, modelStereo, TAG_INSTRUMENTED_DATE,
                java.time.LocalDateTime.now().withNano(0).toString());
        log.info("Instrumented model '" + project.getName() + "' rootIRI=" + rootIri + " version=" + version);
    }

    /**
     * Removes ALL semantic instrumentation from the project: every SemanticAlignment
     * application on any element, plus the model-level SemanticModel stereotype. MUST run
     * inside a write session. Returns the number of stereotype applications removed.
     * Trace: design/use_cases.md UC-1.5
     */
    public static int removeAllInstrumentation(Project project) {
        int removed = 0;
        Stereotype alignStereo = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        if (alignStereo != null) {
            // Copy first: removeStereotype mutates the underlying stereotyped-elements set.
            for (Element el : new java.util.ArrayList<>(
                    StereotypesHelper.getStereotypedElements(alignStereo))) {
                StereotypesHelper.removeStereotype(el, alignStereo);
                removed++;
            }
        }
        Stereotype modelStereo = StereotypesHelper.getStereotype(project, MODEL_STEREOTYPE_NAME);
        if (modelStereo != null) {
            for (Element el : new java.util.ArrayList<>(
                    StereotypesHelper.getStereotypedElements(modelStereo))) {
                StereotypesHelper.removeStereotype(el, modelStereo);
                removed++;
            }
        }
        log.info("Removed " + removed + " semantic stereotype applications from '"
                + project.getName() + "'");
        return removed;
    }

    /** Count of elements currently carrying the SemanticAlignment stereotype. */
    public static int alignedElementCount(Project project) {
        Stereotype alignStereo = StereotypesHelper.getStereotype(project, STEREOTYPE_NAME);
        return alignStereo == null ? 0 : StereotypesHelper.getStereotypedElements(alignStereo).size();
    }
}
