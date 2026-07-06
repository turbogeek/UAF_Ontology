package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import com.nomagic.actions.AMConfigurator;
import com.nomagic.actions.NMAction;
import com.nomagic.magicdraw.actions.ActionsID;
import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.actions.MDActionsCategory;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper;
import com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager;
import org.apache.log4j.Logger;

import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;

/**
 * Adds a "Semantic Alignment" submenu under the Tools menu with explicit
 * Instrument / Remove Instrumentation commands. Owner requirement (2026-07-06):
 * instrumenting a model with semantics is a deliberate user action, not a silent
 * mount-on-first-use, and it must be reversible (un-instrument removes the data).
 * Trace: design/use_cases.md UC-1.1, UC-1.5
 */
public class SemanticMenuConfigurator implements AMConfigurator {

    private static final Logger log = Logger.getLogger(SemanticMenuConfigurator.class);

    static final String SUBMENU_ID = "SEMANTIC_ALIGNMENT_MENU";
    static final String INSTRUMENT_ID = "SEMANTIC_INSTRUMENT";
    static final String UNINSTRUMENT_ID = "SEMANTIC_UNINSTRUMENT";
    static final String RELOAD_ID = "SEMANTIC_RELOAD_CATALOG";

    @Override
    public void configure(ActionsManager manager) {
        NMAction toolsAction = manager.getActionFor(ActionsID.TOOLS);
        if (!(toolsAction instanceof ActionsCategory)) {
            log.warn("Tools menu category (" + ActionsID.TOOLS + ") not found; "
                    + "Semantic Alignment submenu not installed.");
            return;
        }
        // configure() can run multiple times (menu rebuilds); don't duplicate the submenu.
        if (manager.getActionFor(INSTRUMENT_ID) != null) {
            return;
        }
        ActionsCategory tools = (ActionsCategory) toolsAction;
        MDActionsCategory submenu = new MDActionsCategory(SUBMENU_ID, "Semantic Alignment");
        submenu.setNested(true);
        submenu.addAction(new InstrumentAction());
        submenu.addAction(new UnInstrumentAction());
        submenu.addAction(new ReloadCatalogAction());
        tools.addAction(submenu);
        log.info("Installed Tools > Semantic Alignment submenu.");
        DiagnosticLog.event("LIFECYCLE", "Tools > Semantic Alignment submenu installed");
    }

    private static Project activeProject() {
        return Application.getInstance().getProject();
    }

    private static java.awt.Frame dialogParent() {
        try {
            return Application.getInstance().getMainFrame();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tools > Semantic Alignment > Instrument Model — the explicit opt-in (UC-1.1). */
    private static final class InstrumentAction extends MDAction {
        InstrumentAction() {
            super(INSTRUMENT_ID, "Instrument Model for Semantics…", null, null);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Project project = activeProject();
            if (project == null) {
                JOptionPane.showMessageDialog(dialogParent(), "Open a project first.",
                        "Semantic Alignment", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (StereotypeManager.isInstrumented(project)) {
                JOptionPane.showMessageDialog(dialogParent(),
                        "This model is already instrumented for semantics.",
                        "Semantic Alignment", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            // Mount the shipped profile module OUTSIDE any session.
            if (!StereotypeManager.ensureProfileAvailable(project)) {
                JOptionPane.showMessageDialog(dialogParent(),
                        "The Semantic Alignment Profile could not be mounted.",
                        "Semantic Alignment", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String rootIri = JOptionPane.showInputDialog(dialogParent(),
                    "Ontology root IRI for this model's derived ontology:",
                    StereotypeManager.defaultRootIri(project));
            if (rootIri == null) {
                return; // cancelled
            }
            rootIri = rootIri.trim();
            if (rootIri.isEmpty()) {
                rootIri = StereotypeManager.defaultRootIri(project);
            }
            String version = JOptionPane.showInputDialog(dialogParent(),
                    "Ontology version:", "1.0.0");
            if (version == null) {
                return;
            }
            version = version.trim().isEmpty() ? "1.0.0" : version.trim();
            final String fRoot = rootIri;
            final String fVer = version;
            try {
                TransactionWrapper.executeWrite(project, "Instrument Model for Semantics",
                        () -> StereotypeManager.applyModelInstrumentation(project, fRoot, fVer,
                                "semantic-alignment-plugin/" + SemanticAlignmentPlugin.VERSION));
                DiagnosticLog.event("INSTRUMENT", project.getName()
                        + " rootIRI=" + fRoot + " version=" + fVer);
                JOptionPane.showMessageDialog(dialogParent(),
                        "Model instrumented for semantics.\n\nRoot IRI: " + fRoot
                                + "\nVersion: " + fVer
                                + "\n\nSelect an element and use the Semantic Alignment panel to align it.",
                        "Semantic Alignment", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                log.error("Instrument model failed", ex);
                DiagnosticLog.event("INSTRUMENT", project.getName() + " FAILED: " + ex.getMessage());
                JOptionPane.showMessageDialog(dialogParent(),
                        "Instrumentation failed: " + ex.getMessage(),
                        "Semantic Alignment", JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void updateState() {
            Project project = activeProject();
            setEnabled(project != null && !StereotypeManager.isInstrumented(project));
        }
    }

    /** Tools > Semantic Alignment > Remove Instrumentation — reversible (UC-1.5). */
    private static final class UnInstrumentAction extends MDAction {
        UnInstrumentAction() {
            super(UNINSTRUMENT_ID, "Remove Semantic Instrumentation…", null, null);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Project project = activeProject();
            if (project == null) {
                return;
            }
            int aligned = StereotypeManager.alignedElementCount(project);
            int choice = JOptionPane.showConfirmDialog(dialogParent(),
                    "This removes ALL semantic instrumentation from this model:\n"
                            + "  • " + aligned + " element alignment(s)\n"
                            + "  • the model-level ontology root IRI + version\n\n"
                            + "The captured semantics will be deleted (undoable in this session).\n"
                            + "Continue?",
                    "Remove Semantic Instrumentation",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                final int[] removed = new int[1];
                TransactionWrapper.executeWrite(project, "Remove Semantic Instrumentation",
                        () -> removed[0] = StereotypeManager.removeAllInstrumentation(project));
                DiagnosticLog.event("UNINSTRUMENT", project.getName()
                        + " removed=" + removed[0] + " applications");
                JOptionPane.showMessageDialog(dialogParent(),
                        "Removed " + removed[0] + " semantic stereotype application(s). "
                                + "The model is no longer instrumented.",
                        "Semantic Alignment", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                log.error("Remove instrumentation failed", ex);
                DiagnosticLog.event("UNINSTRUMENT", project.getName() + " FAILED: " + ex.getMessage());
                JOptionPane.showMessageDialog(dialogParent(),
                        "Removal failed: " + ex.getMessage(),
                        "Semantic Alignment", JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void updateState() {
            Project project = activeProject();
            setEnabled(project != null && StereotypeManager.isInstrumented(project));
        }
    }

    /** Tools > Semantic Alignment > Reload Ontology Catalog — on-demand catalog refresh. */
    private static final class ReloadCatalogAction extends MDAction {
        ReloadCatalogAction() {
            super(RELOAD_ID, "Reload Ontology Catalog", null, null);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                String summary = SemanticAlignmentPlugin.reloadCatalog();
                DiagnosticLog.event("CATALOG", "Reloaded via menu: " + summary);
                JOptionPane.showMessageDialog(dialogParent(),
                        "Ontology catalog reloaded.\n" + summary,
                        "Semantic Alignment", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                log.error("Catalog reload failed", ex);
                JOptionPane.showMessageDialog(dialogParent(),
                        "Catalog reload failed: " + ex.getMessage(),
                        "Semantic Alignment", JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void updateState() {
            setEnabled(true);
        }
    }
}
