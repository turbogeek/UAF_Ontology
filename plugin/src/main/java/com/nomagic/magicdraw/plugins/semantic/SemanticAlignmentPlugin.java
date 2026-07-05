package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper;
import com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.Browser;
import com.nomagic.magicdraw.ui.browser.ContainmentTree;
import com.nomagic.magicdraw.ui.browser.Node;
import com.nomagic.magicdraw.ui.browser.WindowComponent;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.ui.ExtendedPanel;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Main lifecycle entry point for the UAF/SysML Semantic Integration Plugin.
 * Registers one sidebar panel per project browser; each panel is bound to its own
 * project and selection so multiple open projects never cross-talk. All user-visible
 * reactions are journaled through DiagnosticLog so the REST test harness can assert
 * on GUI behavior.
 * Trace: PLG-REQ-01, PLG-REQ-02, PLG-REQ-04, PLG-REQ-05
 */
public class SemanticAlignmentPlugin extends Plugin {

    private static final Logger log = Logger.getLogger(SemanticAlignmentPlugin.class);
    private static final String COMPONENT_ID = "SEMANTIC_ALIGNMENT_SIDEBAR";
    private static final String COMPONENT_NAME = "Semantic Alignment";
    static final String VERSION = "2.2.0";

    private static final SBVREngine SBVR_ENGINE = new SBVREngine();

    // Written once in init(); the deployed plugin folder holds the default SHACL shapes.
    private static volatile File pluginDirectory;

    private static final WindowComponentInfo info = new WindowComponentInfo(
            COMPONENT_ID,
            COMPONENT_NAME,
            null, // Custom icon can be registered here
            ProjectWindowsManager.SIDE_WEST,
            ProjectWindowsManager.STATE_DOCKED,
            true
    );

    @Override
    public void init() {
        log.info("Initializing UAF / SysML Semantic Alignment Plugin " + VERSION + "...");
        DiagnosticLog.event("LIFECYCLE", "Plugin init (version " + VERSION + ")");
        if (getDescriptor() != null) {
            pluginDirectory = getDescriptor().getPluginDirectory();
        }

        // Export/validation logic stays usable from CI and harness scripts; only the
        // sidebar needs a display (spec 7.1 headless duality).
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Headless environment detected - skipping sidebar registration.");
            DiagnosticLog.event("LIFECYCLE", "Headless mode: sidebar registration skipped");
            return;
        }

        // Without this, JavaFX shuts its toolkit down when the last JFXPanel disappears
        // (e.g. on project close) and every later Platform.runLater call is dropped.
        Platform.setImplicitExit(false);

        // Integrated REST test harness: auto-starts on port 8765 so integration tests
        // run zero-touch after Cameo launches (no manual Tools > Macros step).
        HarnessBootstrap.startAsync(pluginDirectory);

        // One panel per project browser: the panel captures its own project so that
        // selections, mappings, and audits always target the project they came from.
        Browser.addBrowserInitializer(new Browser.BrowserInitializer() {
            @Override
            public void init(Browser browser, Project project) {
                SemanticBrowserPanel panel = new SemanticBrowserPanel(project);
                browser.addPanel(panel);
                panel.hookSelectionListener(browser);
                DiagnosticLog.event("LIFECYCLE", "Sidebar registered for project: " + project.getName());
            }

            @Override
            public WindowComponentInfoRegistration getInfo() {
                return new WindowComponentInfoRegistration(info, null);
            }
        });
    }

    @Override
    public boolean close() {
        log.info("Closing Semantic Alignment Plugin...");
        DiagnosticLog.event("LIFECYCLE", "Plugin close");
        DiagnosticLog.shutdown();
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    /**
     * SHACL shapes resolve from -Dsemantic.plugin.shapes first (test override), then from
     * the deployed plugin folder - never from a hard-coded path (spec 8.1). The plugin
     * ships semantic-plugin-shapes.ttl matching the exporter vocabulary; the UAF
     * traceability shapes remain available for requirement-satisfaction graphs.
     */
    static File resolveShapesFile() {
        String override = System.getProperty("semantic.plugin.shapes");
        if (override != null && !override.isBlank()) {
            File file = new File(override);
            if (file.exists()) {
                return file;
            }
        }
        File dir = pluginDirectory;
        if (dir != null) {
            File bundled = new File(dir, "semantic-plugin-shapes.ttl");
            if (bundled.exists()) {
                return bundled;
            }
            File traceability = new File(dir, "uaf_traceability_shapes.ttl");
            if (traceability.exists()) {
                return traceability;
            }
        }
        return null;
    }

    /**
     * TBox ontologies for the consistency check: every .ttl in the plugin's tbox/
     * directory. Integration tests can drop axiom files in at runtime (no restart
     * needed - the folder is re-read on every audit). Without a TBox the reasoner
     * can only do structural checks, which the audit diagnostic line records.
     */
    static List<File> resolveTBoxFiles() {
        List<File> result = new ArrayList<>();
        String override = System.getProperty("semantic.plugin.tbox");
        if (override != null && !override.isBlank()) {
            File file = new File(override);
            if (file.exists()) {
                result.add(file);
            }
        }
        File dir = pluginDirectory;
        if (dir != null) {
            File tboxDir = new File(dir, "tbox");
            File[] files = tboxDir.listFiles((d, name) -> name.toLowerCase().endsWith(".ttl"));
            if (files != null) {
                for (File file : files) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    /**
     * Swing browser panel container holding the JavaFX panel. Bound to exactly one
     * project; all model interaction flows through this instance.
     */
    private static final class SemanticBrowserPanel extends ExtendedPanel implements WindowComponent {

        private final Project project;
        private volatile Element selectedElement;

        private JFXPanel jfxPanel;
        // JavaFX controls; only touched on the FX thread and null until initFX has run.
        private Label selectionLabel;
        private Label typeLabel;
        private TextArea sbvrArea;
        private TextField searchField;
        private Button auditButton;
        private Label statusBadge;
        private TextArea consoleArea;

        // Selections can arrive before the async JavaFX layout exists; the latest one is
        // parked here and replayed at the end of initFX instead of being dropped.
        private volatile boolean fxReady;
        private volatile String[] pendingSelection;

        SemanticBrowserPanel(Project project) {
            super(new BorderLayout());
            this.project = project;

            JLabel loadingLabel = new JLabel("Loading Semantic Alignment Dashboard...");
            loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
            loadingLabel.setForeground(new Color(148, 163, 184)); // Muted slate color
            add(loadingLabel, BorderLayout.CENTER);

            // Initialize JavaFX JFXPanel asynchronously on JavaFX Application Thread
            SwingUtilities.invokeLater(() -> {
                jfxPanel = new JFXPanel();
                add(jfxPanel, BorderLayout.CENTER);
                Platform.runLater(() -> {
                    initFX(jfxPanel);
                    SwingUtilities.invokeLater(() -> {
                        remove(loadingLabel);
                        revalidate();
                        repaint();
                    });
                });
            });
        }

        /**
         * Attaches a Swing selection listener to this browser's containment tree so the
         * sidebar follows the modeler's selection. Trace: PLG-REQ-04
         */
        void hookSelectionListener(Browser browser) {
            try {
                ContainmentTree containmentTree = browser.getContainmentTree();
                if (containmentTree == null) {
                    DiagnosticLog.event("WARN", "Containment tree unavailable; live selection disabled");
                    return;
                }
                containmentTree.getTree().addTreeSelectionListener(event -> {
                    if (project.isProjectClosed() || project.isProjectDisposed()) {
                        return; // stale listener after project close - ignore
                    }
                    Node node = containmentTree.getSelectedNode();
                    Object userObject = node == null ? null : node.getUserObject();
                    if (userObject instanceof Element) {
                        handleSelection((Element) userObject);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to attach browser selection listener", e);
                DiagnosticLog.event("ERROR", "Selection listener hookup failed: " + e);
            }
        }

        /**
         * Runs on the EDT (tree selection events); model reads happen here, UI updates
         * are republished to the JavaFX thread. Trace: PLG-REQ-04
         */
        private void handleSelection(Element element) {
            try {
                selectedElement = element;
                String name = element.getHumanName();
                List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
                String typeText = stereotypes.isEmpty()
                        ? element.getHumanType()
                        : stereotypes.stream().map(Stereotype::getName).collect(Collectors.joining(", "));
                String conceptURI = readMappedConceptURI(element);
                String sbvr = conceptURI != null
                        ? SBVR_ENGINE.generatePlainSBVR(name, conceptURI, null, null)
                        : "No semantic alignment applied. Enter a concept IRI below and press Enter to map.";
                DiagnosticLog.event("SELECTION", name + " | stereotypes=" + typeText
                        + " | concept=" + (conceptURI == null ? "-" : conceptURI) + " | sbvr=" + sbvr);
                showSelection(name, typeText, sbvr);
            } catch (Exception e) {
                log.error("Selection handling failed", e);
                DiagnosticLog.event("ERROR", "Selection handling failed: " + e);
            }
        }

        private String readMappedConceptURI(Element element) {
            Stereotype stereotype = StereotypesHelper.getStereotype(
                    project, StereotypeManager.STEREOTYPE_NAME);
            if (stereotype == null || !StereotypesHelper.hasStereotype(element, stereotype)) {
                return null;
            }
            // Tagged values come back as a List regardless of multiplicity; unwrap the first.
            List<?> values = StereotypesHelper.getStereotypePropertyValue(
                    element, stereotype, StereotypeManager.PROPERTY_NAME);
            if (values == null || values.isEmpty() || values.get(0) == null) {
                return null;
            }
            String raw = values.get(0).toString().trim();
            return raw.isEmpty() ? null : raw;
        }

        /**
         * Called from the JavaFX thread (concept field action): applies the typed concept
         * IRI to the selected element. Model writes must happen inside a session on the
         * EDT, so the FX thread only queues the work. The session is opened on the
         * element's own project, which by construction is this panel's project.
         * Trace: PLG-REQ-03, PLG-REQ-06
         */
        private void applyMappingFromUI(String conceptURI) {
            Element element = selectedElement;
            if (element == null) {
                DiagnosticLog.event("MAPPING", "Rejected: no element selected");
                appendConsole("[FAIL] Select an element in the containment tree first.");
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    if (project.isProjectClosed() || project.isProjectDisposed()
                            || project.isDisposed(element)) {
                        DiagnosticLog.event("MAPPING", "Rejected: project or element no longer alive");
                        appendConsole("[FAIL] The project or element is no longer open.");
                        return;
                    }
                    if (!element.isEditable()) {
                        DiagnosticLog.event("MAPPING", element.getHumanName()
                                + " -> " + conceptURI + " | status=REJECTED read-only element");
                        appendConsole("[FAIL] Element is read-only (library or locked element).");
                        return;
                    }
                    Project owner = Project.getProject(element);
                    TransactionWrapper.executeWrite(owner != null ? owner : project,
                            "Apply Semantic Mapping",
                            () -> StereotypeManager.applySemanticMapping(element, conceptURI));
                    DiagnosticLog.event("MAPPING", element.getHumanName() + " -> " + conceptURI + " | status=OK");
                    appendConsole("[OK] Mapped to " + conceptURI);
                    handleSelection(element); // refresh the sidebar with the new alignment
                } catch (Exception e) {
                    log.error("Semantic mapping failed", e);
                    DiagnosticLog.event("MAPPING", element.getHumanName() + " -> " + conceptURI
                            + " | status=FAILED | " + e.getMessage());
                    appendConsole("[FAIL] " + e.getMessage());
                }
            });
        }

        /**
         * Called from the JavaFX thread (audit button). The model snapshot (RDF export)
         * runs on the EDT - the MagicDraw model store is single-threaded and traversing
         * it from a worker races concurrent edits. Only the reasoner and SHACL run on
         * the background worker (spec 7.2 non-blocking UI for the expensive part).
         * Trace: PLG-REQ-05, PLG-REQ-06
         */
        private void runAuditAsync() {
            if (project.isProjectClosed() || project.isProjectDisposed()) {
                DiagnosticLog.event("AUDIT", "Rejected: project no longer open");
                showAuditResult(null, 0, List.of("Project is no longer open."));
                return;
            }
            showAuditRunning();
            Thread worker = new Thread(() -> {
                try {
                    final String[] turtleHolder = new String[1];
                    final Exception[] exportError = new Exception[1];
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            if (project.isProjectClosed() || project.isProjectDisposed()) {
                                exportError[0] = new IllegalStateException("Project closed before export.");
                                return;
                            }
                            turtleHolder[0] = new SemanticRDFExporter(project).exportToTurtleString();
                        } catch (Exception e) {
                            exportError[0] = e;
                        }
                    });
                    if (exportError[0] != null) {
                        throw exportError[0];
                    }
                    String turtle = turtleHolder[0];

                    // The exported graph is persisted next to the diagnostic log so harness
                    // tests can parse exactly what the audit validated.
                    Path exportFile = DiagnosticLog.getLogDirectory().resolve("last-audit-export.ttl");
                    Files.writeString(exportFile, turtle, StandardCharsets.UTF_8);

                    SHACLValidator validator = new SHACLValidator();
                    List<File> tbox = resolveTBoxFiles();
                    SHACLValidator.ReasonerResult reasoning = validator.runHermitReasoner(turtle, tbox);
                    File shapesFile = resolveShapesFile();
                    SHACLValidator.SHACLAuditReport audit = shapesFile == null
                            ? new SHACLValidator.SHACLAuditReport(1, List.of(
                                    "SHACL shapes file not found. Set -Dsemantic.plugin.shapes or deploy semantic-plugin-shapes.ttl with the plugin."))
                            : validator.runSHACLAudit(turtle, shapesFile.getAbsolutePath());

                    List<String> messages = new ArrayList<>(reasoning.getMessages());
                    messages.addAll(audit.getMessages());
                    DiagnosticLog.event("AUDIT", "project=" + project.getName()
                            + " | consistent=" + reasoning.isConsistent()
                            + " | shaclViolations=" + audit.getViolationsCount()
                            + " | tboxFiles=" + tbox.size()
                            + " | shapes=" + (shapesFile == null ? "-" : shapesFile.getName())
                            + " | export=" + exportFile
                            + (messages.isEmpty() ? "" : " | " + String.join(" ;; ", messages)));
                    showAuditResult(reasoning.isConsistent(), audit.getViolationsCount(), messages);
                } catch (Throwable t) {
                    log.error("Semantic audit failed", t);
                    DiagnosticLog.event("ERROR", "Audit failed: " + t);
                    showAuditResult(null, 0, List.of("Audit error: " + t.getMessage()));
                }
            }, "semantic-alignment-audit");
            worker.setDaemon(true);
            worker.start();
        }

        /**
         * Renders the JavaFX UI layout inside the JFXPanel (spec 12.4 layout).
         */
        private void initFX(JFXPanel panel) {
            VBox root = new VBox(10);
            root.setPadding(new Insets(12));
            root.setStyle("-fx-background-color: #0f172a;"); // Dark slate background matching graphify theme

            // Top section: Selected element card
            selectionLabel = new Label("Selected Element: (none)");
            selectionLabel.setStyle("-fx-text-fill: #f8fafc; -fx-font-weight: bold; -fx-font-size: 13px;");

            typeLabel = new Label("Stereotype: -");
            typeLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");

            VBox metaCard = new VBox(4, selectionLabel, typeLabel);
            metaCard.setPadding(new Insets(10));
            metaCard.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 6px; -fx-border-color: #334155; -fx-border-radius: 6px;");
            root.getChildren().add(metaCard);

            // Middle section: SBVR View
            Label sbvrHeader = new Label("SBVR Structured English");
            sbvrHeader.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-font-weight: bold;");

            sbvrArea = new TextArea("Select an element in the containment tree.");
            sbvrArea.setEditable(false);
            sbvrArea.setWrapText(true);
            sbvrArea.setPrefHeight(90);
            sbvrArea.setStyle("-fx-control-inner-background: #1e293b; -fx-text-fill: #38bdf8; -fx-font-family: \"Courier New\", monospace; -fx-font-size: 12px;");
            root.getChildren().addAll(sbvrHeader, sbvrArea);

            // Concept mapping input: Enter applies the IRI to the selected element
            Label searchHeader = new Label("Align with Ontology Concept");
            searchHeader.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-font-weight: bold;");

            searchField = new TextField();
            searchField.setPromptText("Concept IRI (e.g. sumo:MilitaryBase) + Enter");
            searchField.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #f8fafc; -fx-prompt-text-fill: #64748b; -fx-border-color: #334155; -fx-border-radius: 4px;");
            searchField.setOnAction(event -> {
                String text = searchField.getText();
                if (text != null && !text.isBlank()) {
                    applyMappingFromUI(text.trim());
                }
            });
            root.getChildren().addAll(searchHeader, searchField);

            // Bottom section: Validation dashboard
            auditButton = new Button("Run Audit");
            auditButton.setStyle("-fx-background-color: #1d4ed8; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 11px;");
            auditButton.setOnAction(event -> runAuditAsync());

            statusBadge = new Label("STATUS: NOT AUDITED");
            statusBadge.setStyle(badgeStyle("#475569"));
            root.getChildren().addAll(auditButton, statusBadge);

            consoleArea = new TextArea();
            consoleArea.setEditable(false);
            consoleArea.setWrapText(true);
            consoleArea.setPrefHeight(70);
            consoleArea.setStyle("-fx-control-inner-background: #020617; -fx-text-fill: #94a3b8; -fx-font-family: \"Courier New\", monospace; -fx-font-size: 10px;");
            root.getChildren().add(consoleArea);

            Scene scene = new Scene(root, 300, 500);
            panel.setScene(scene);

            fxReady = true;
            String[] pending = pendingSelection;
            if (pending != null) {
                pendingSelection = null;
                selectionLabel.setText("Selected Element: " + pending[0]);
                typeLabel.setText("Stereotype: " + pending[1]);
                sbvrArea.setText(pending[2]);
            }
        }

        private static String badgeStyle(String colorHex) {
            return "-fx-background-color: " + colorHex + "; -fx-text-fill: #ffffff; -fx-padding: 4px 8px; "
                    + "-fx-background-radius: 4px; -fx-font-weight: bold; -fx-font-size: 11px;";
        }

        private void showSelection(String name, String typeText, String sbvr) {
            if (!fxReady) {
                // Park it; initFX replays the latest selection instead of dropping it.
                pendingSelection = new String[]{name, typeText, sbvr};
                return;
            }
            Platform.runLater(() -> {
                selectionLabel.setText("Selected Element: " + name);
                typeLabel.setText("Stereotype: " + typeText);
                sbvrArea.setText(sbvr);
            });
        }

        private void appendConsole(String message) {
            if (!fxReady) {
                return;
            }
            Platform.runLater(() -> consoleArea.appendText(message + "\n"));
        }

        private void showAuditRunning() {
            if (!fxReady) {
                return;
            }
            Platform.runLater(() -> {
                auditButton.setDisable(true);
                statusBadge.setText("STATUS: AUDITING...");
                statusBadge.setStyle(badgeStyle("#b45309"));
            });
        }

        /**
         * @param consistent reasoner verdict, or null when the audit itself errored
         */
        private void showAuditResult(Boolean consistent, int violations, List<String> messages) {
            if (!fxReady) {
                return;
            }
            Platform.runLater(() -> {
                auditButton.setDisable(false);
                if (consistent == null) {
                    statusBadge.setText("STATUS: AUDIT ERROR");
                    statusBadge.setStyle(badgeStyle("#475569"));
                } else if (consistent && violations == 0) {
                    statusBadge.setText("STATUS: CONSISTENT");
                    statusBadge.setStyle(badgeStyle("#059669"));
                } else {
                    statusBadge.setText("STATUS: " + (violations > 0
                            ? violations + " VIOLATION(S)" : "INCONSISTENT"));
                    statusBadge.setStyle(badgeStyle("#dc2626"));
                }
                for (String message : messages) {
                    consoleArea.appendText(message + "\n");
                }
            });
        }

        @Override
        public WindowComponentInfo getInfo() {
            return info;
        }

        @Override
        public WindowComponentContent getContent() {
            return new SemanticBrowserWindowComponentContent(this);
        }
    }

    /**
     * Browser container representing the focus/activation controls inside the Swing frame.
     */
    private static final class SemanticBrowserWindowComponentContent implements WindowComponentContent {
        private final JPanel panel;

        public SemanticBrowserWindowComponentContent(JPanel panel) {
            this.panel = panel;
        }

        @Override
        public java.awt.Component getWindowComponent() {
            return panel;
        }

        @Override
        public java.awt.Component getDefaultFocusComponent() {
            return panel;
        }
    }
}
