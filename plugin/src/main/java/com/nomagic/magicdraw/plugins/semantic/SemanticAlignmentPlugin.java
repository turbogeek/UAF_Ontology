package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.semantic.align.CatalogLoader;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.StereotypeRouter;
import com.nomagic.magicdraw.plugins.semantic.align.SuggestionRanker;
import com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper;
import com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager;
import com.nomagic.magicdraw.plugins.semantic.rest.SemanticRestService;
import com.nomagic.magicdraw.plugins.semantic.view.OntologyViewPanel;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.Browser;
import com.nomagic.magicdraw.ui.browser.ContainmentTree;
import com.nomagic.magicdraw.ui.browser.Node;
import com.nomagic.magicdraw.ui.browser.WindowComponent;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.ui.ExtendedPanel;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main lifecycle entry point for the UAF/SysML Semantic Integration Plugin.
 * Registers one sidebar panel per project browser; each panel is bound to its own
 * project and selection so multiple open projects never cross-talk. All user-visible
 * reactions are journaled through DiagnosticLog so the REST test harness can assert
 * on GUI behavior.
 *
 * The sidebar is pure Swing. The design spec (12.4) originally called for a JavaFX
 * JFXPanel, but Cameo's jlink'd runtime omits the jdk.unsupported.desktop module that
 * javafx.embed.swing requires (jdk.swing.interop), so JFXPanel cannot function in the
 * target install. Swing meets the same layout spec with one toolkit and no JVM flags
 * (spec 8.1 portability wins over the mockup technology choice).
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

    // Built once on a background thread at init; null until the catalog is loaded.
    private static volatile SuggestionRanker suggestionRanker;
    private static volatile org.apache.jena.rdf.model.Model catalogModel;
    private static volatile SemanticRestService restService;

    private static final WindowComponentInfo info = new WindowComponentInfo(
            COMPONENT_ID,
            COMPONENT_NAME,
            null, // Custom icon can be registered here
            ProjectWindowsManager.SIDE_WEST,
            ProjectWindowsManager.STATE_DOCKED,
            true
    );

    private static final WindowComponentInfo ontologyViewInfo = new WindowComponentInfo(
            "SEMANTIC_ONTOLOGY_VIEW",
            "Semantic Ontology",
            null,
            ProjectWindowsManager.SIDE_EAST,
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

        // Integrated REST test harness: auto-starts on port 8765 so integration tests
        // run zero-touch after Cameo launches (no manual Tools > Macros step).
        HarnessBootstrap.startAsync(pluginDirectory);

        // Fire Jena ARQ's ServiceLoader initialization deterministically inside OUR
        // classloader before any SPARQL runs (SPI inside foreign classloaders is the
        // known failure mode in Cameo).
        try {
            org.apache.jena.query.ARQ.init();
        } catch (Throwable t) {
            log.error("ARQ init failed", t);
            DiagnosticLog.event("ERROR", "ARQ init failed: " + t);
        }

        // Alignment catalog: loaded off the startup path; the sidebar degrades to a
        // "catalog loading/missing" hint until this completes.
        Thread catalogLoader = new Thread(() -> {
            CatalogLoader.LoadedCatalog catalog = CatalogLoader.loadAll(
                    CatalogLoader.resolveCatalogDirectory(pluginDirectory));
            catalogModel = catalog.model();
            suggestionRanker = new SuggestionRanker(catalog.index(), StereotypeRouter.load(pluginDirectory));
        }, "semantic-catalog-loader");
        catalogLoader.setDaemon(true);
        catalogLoader.start();

        // UX click budgets are measured, not aspirational (v3 plan section 5)
        UxMetrics.install();

        // Plugin-owned REST surface (8766): /sparql for scenarios and future LLM
        // assistants, /metrics for the click budgets. Dataset = catalog TBox + live
        // project ABox snapshotted on the EDT per request.
        restService = new SemanticRestService(() -> {
            org.apache.jena.rdf.model.Model tbox = catalogModel;
            org.apache.jena.rdf.model.Model abox = snapshotActiveProjectModel();
            if (tbox == null) {
                return abox;
            }
            if (abox == null) {
                return tbox;
            }
            return org.apache.jena.rdf.model.ModelFactory.createUnion(tbox, abox);
        });
        restService.start();

        // One panel per project browser: the panel captures its own project so that
        // selections, mappings, and audits always target the project they came from.
        Browser.addBrowserInitializer(new Browser.BrowserInitializer() {
            @Override
            public void init(Browser browser, Project project) {
                SemanticBrowserPanel panel = new SemanticBrowserPanel(project);
                browser.addPanel(panel);
                panel.hookSelectionListener(browser);
                browser.addPanel(new OntologyViewPanel(project,
                        () -> catalogModel, ontologyViewInfo));
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
        if (restService != null) {
            restService.stop();
        }
        DiagnosticLog.shutdown();
        return true;
    }

    /** ABox snapshot of the active project, taken on the EDT; null when none is open. */
    private static org.apache.jena.rdf.model.Model snapshotActiveProjectModel() {
        try {
            final org.apache.jena.rdf.model.Model[] holder = new org.apache.jena.rdf.model.Model[1];
            SwingUtilities.invokeAndWait(() -> {
                Project project = com.nomagic.magicdraw.core.Application.getInstance().getProject();
                if (project != null && !project.isProjectClosed() && !project.isProjectDisposed()) {
                    holder[0] = new SemanticRDFExporter(project).exportToModel();
                }
            });
            return holder[0];
        } catch (Exception e) {
            log.error("Project snapshot for REST dataset failed", e);
            return null;
        }
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
     * Pure-Swing sidebar panel bound to exactly one project; all model interaction
     * flows through this instance. Components carry stable names so integration tests
     * can locate them in the component tree.
     */
    private static final class SemanticBrowserPanel extends ExtendedPanel implements WindowComponent {

        private static final Color BG_DARK = new Color(0x0f172a);
        private static final Color BG_CARD = new Color(0x1e293b);
        private static final Color FG_MAIN = new Color(0xf8fafc);
        private static final Color FG_MUTED = new Color(0x94a3b8);
        private static final Color FG_ACCENT = new Color(0x38bdf8);
        private static final Color BADGE_NEUTRAL = new Color(0x475569);
        private static final Color BADGE_RUNNING = new Color(0xb45309);
        private static final Color BADGE_OK = new Color(0x059669);
        private static final Color BADGE_BAD = new Color(0xdc2626);

        private final Project project;
        private volatile Element selectedElement;

        private final JLabel selectionLabel = new JLabel("Selected Element: (none)");
        private final JLabel typeLabel = new JLabel("Stereotype: -");
        private final JTextArea sbvrArea = new JTextArea("Select an element in the containment tree.");
        private final DefaultListModel<ConceptSuggestion> suggestionModel = new DefaultListModel<>();
        private final JList<ConceptSuggestion> suggestionList = new JList<>(suggestionModel);
        private final JTextField searchField = new JTextField();
        private final JButton auditButton = new JButton("Run Audit");
        private final JLabel statusBadge = new JLabel("STATUS: NOT AUDITED");
        private final JTextArea consoleArea = new JTextArea();

        // Context the ranker scores against; updated on every tree selection.
        private volatile String selectedName = "";
        private volatile List<String> selectedStereotypes = List.of();
        private final Timer searchDebounce = new Timer(150, e -> refreshSuggestions());

        SemanticBrowserPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            buildUi();
        }

        private void buildUi() {
            setName("semantic.sidebar");
            JPanel root = new JPanel();
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
            root.setBackground(BG_DARK);
            root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);

            // Top section: selected element card (spec 12.4)
            selectionLabel.setName("semantic.selectionLabel");
            selectionLabel.setForeground(FG_MAIN);
            selectionLabel.setFont(selectionLabel.getFont().deriveFont(Font.BOLD, 13f));
            typeLabel.setName("semantic.typeLabel");
            typeLabel.setForeground(FG_MUTED);
            JPanel metaCard = new JPanel();
            metaCard.setLayout(new BoxLayout(metaCard, BoxLayout.Y_AXIS));
            metaCard.setBackground(BG_CARD);
            metaCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x334155)),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            metaCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            metaCard.add(selectionLabel);
            metaCard.add(typeLabel);
            root.add(metaCard);
            root.add(Box.createVerticalStrut(10));

            // Middle section: SBVR view
            JLabel sbvrHeader = new JLabel("SBVR Structured English");
            sbvrHeader.setForeground(FG_MUTED);
            sbvrHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(sbvrHeader);
            sbvrArea.setName("semantic.sbvrArea");
            sbvrArea.setEditable(false);
            sbvrArea.setLineWrap(true);
            sbvrArea.setWrapStyleWord(true);
            sbvrArea.setBackground(BG_CARD);
            sbvrArea.setForeground(FG_ACCENT);
            sbvrArea.setFont(monoFont);
            JScrollPane sbvrScroll = new JScrollPane(sbvrArea);
            sbvrScroll.setPreferredSize(new Dimension(280, 90));
            sbvrScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(sbvrScroll);
            root.add(Box.createVerticalStrut(10));

            // Suggested mappings: ranked for the selected element with zero keystrokes;
            // ONE CLICK on a row applies the mapping (v3 plan click budget).
            JLabel suggestHeader = new JLabel("Suggested Mappings (click to apply)");
            suggestHeader.setForeground(FG_MUTED);
            suggestHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(suggestHeader);
            suggestionList.setName("semantic.suggestionList");
            suggestionList.setBackground(BG_CARD);
            suggestionList.setForeground(FG_MAIN);
            suggestionList.setSelectionBackground(new Color(0x0369a1));
            suggestionList.setSelectionForeground(Color.WHITE);
            suggestionList.setVisibleRowCount(5);
            suggestionList.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                        int idx, boolean sel, boolean focus) {
                    Component c = super.getListCellRendererComponent(list, value, idx, sel, focus);
                    if (value instanceof ConceptSuggestion s) {
                        setText(String.format("%s   %s   %d%%",
                                s.entry().label(), s.entry().curie(), Math.round(s.score() * 100)));
                        setToolTipText(s.entry().comment().isBlank() ? s.entry().iri() : s.entry().comment());
                    }
                    return c;
                }
            });
            suggestionList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    int row = suggestionList.locationToIndex(event.getPoint());
                    if (row >= 0 && row < suggestionModel.size()) {
                        ConceptSuggestion suggestion = suggestionModel.get(row);
                        DiagnosticLog.event("SUGGEST", "clicked " + suggestion.entry().curie()
                                + " (" + Math.round(suggestion.score() * 100) + "%) for " + selectedName);
                        applyMappingFromUI(suggestion.entry().iri());
                    }
                }
            });
            JScrollPane suggestScroll = new JScrollPane(suggestionList);
            suggestScroll.setPreferredSize(new Dimension(280, 96));
            suggestScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(suggestScroll);
            root.add(Box.createVerticalStrut(10));

            // Search narrows the suggestion list live; Enter keeps the expert path
            // (raw CURIE/IRI) so scripted and power-user flows stay intact.
            JLabel searchHeader = new JLabel("Search Ontology Concepts");
            searchHeader.setForeground(FG_MUTED);
            searchHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(searchHeader);
            searchField.setName("semantic.conceptField");
            searchField.setToolTipText("Type 2-4 characters to narrow suggestions; Enter applies a raw CURIE/IRI");
            searchField.setBackground(BG_CARD);
            searchField.setForeground(FG_MAIN);
            searchField.setCaretColor(FG_MAIN);
            searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
            searchDebounce.setRepeats(false);
            searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
            });
            searchField.addActionListener(event -> {
                String text = searchField.getText();
                if (text != null && !text.isBlank()) {
                    applyMappingFromUI(text.trim());
                }
            });
            root.add(searchField);
            root.add(Box.createVerticalStrut(10));

            // Bottom section: validation dashboard
            auditButton.setName("semantic.auditButton");
            auditButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            auditButton.addActionListener(event -> runAuditAsync());
            root.add(auditButton);
            root.add(Box.createVerticalStrut(6));

            statusBadge.setName("semantic.statusBadge");
            statusBadge.setOpaque(true);
            statusBadge.setBackground(BADGE_NEUTRAL);
            statusBadge.setForeground(Color.WHITE);
            statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 11f));
            statusBadge.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            statusBadge.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(statusBadge);
            root.add(Box.createVerticalStrut(6));

            consoleArea.setName("semantic.consoleArea");
            consoleArea.setEditable(false);
            consoleArea.setLineWrap(true);
            consoleArea.setBackground(new Color(0x020617));
            consoleArea.setForeground(FG_MUTED);
            consoleArea.setFont(monoFont.deriveFont(10f));
            JScrollPane consoleScroll = new JScrollPane(consoleArea);
            consoleScroll.setPreferredSize(new Dimension(280, 70));
            consoleScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(consoleScroll);

            add(new JScrollPane(root), BorderLayout.CENTER);
            DiagnosticLog.event("LIFECYCLE", "Sidebar UI built (Swing)");
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
         * Runs on the EDT (tree selection events); model reads and UI updates share the
         * thread, so no cross-toolkit bridging is needed. Trace: PLG-REQ-04
         */
        private void handleSelection(Element element) {
            try {
                selectedElement = element;
                String name = element.getHumanName();
                // getHumanName prefixes the metatype ("Class EchoBase"); SBVR sentences
                // must speak about the element itself, so prefer the bare name.
                String sbvrSubject = name;
                if (element instanceof NamedElement) {
                    String bare = ((NamedElement) element).getName();
                    if (bare != null && !bare.isEmpty()) {
                        sbvrSubject = bare;
                    }
                }
                List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
                String typeText = stereotypes.isEmpty()
                        ? element.getHumanType()
                        : stereotypes.stream().map(Stereotype::getName).collect(Collectors.joining(", "));
                String conceptURI = readMappedConceptURI(element);
                String sbvr = conceptURI != null
                        ? SBVR_ENGINE.generatePlainSBVR(sbvrSubject, conceptURI, null, null)
                        : "No semantic alignment applied. Enter a concept IRI below and press Enter to map.";
                DiagnosticLog.event("SELECTION", name + " | stereotypes=" + typeText
                        + " | concept=" + (conceptURI == null ? "-" : conceptURI) + " | sbvr=" + sbvr);
                showSelection(name, typeText, sbvr);

                // Zero-keystroke suggestions for the new selection (v3 plan section 1)
                selectedName = sbvrSubject;
                selectedStereotypes = stereotypes.stream().map(Stereotype::getName).toList();
                refreshSuggestions();
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
         * Called on the EDT (concept field action): applies the typed concept IRI to the
         * selected element inside a session on the element's own project.
         * Trace: PLG-REQ-03, PLG-REQ-06
         */
        private void applyMappingFromUI(String conceptURI) {
            Element element = selectedElement;
            if (element == null) {
                DiagnosticLog.event("MAPPING", "Rejected: no element selected");
                appendConsole("[FAIL] Select an element in the containment tree first.");
                return;
            }
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
        }

        /**
         * Called on the EDT (audit button). The model snapshot (RDF export) runs on the
         * EDT - the MagicDraw model store is single-threaded and traversing it from a
         * worker races concurrent edits. Only the reasoner and SHACL run on the
         * background worker (spec 7.2 non-blocking UI for the expensive part).
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

        private void showSelection(String name, String typeText, String sbvr) {
            SwingUtilities.invokeLater(() -> {
                selectionLabel.setText("Selected Element: " + name);
                typeLabel.setText("Stereotype: " + typeText);
                sbvrArea.setText(sbvr);
            });
        }

        /**
         * Re-ranks the suggestion list for the current element; a typed query narrows,
         * an empty field falls back to zero-keystroke element ranking. Index lookups
         * over the ~1,400-concept catalog are sub-millisecond, so this runs inline on
         * the EDT. Trace: v3 plan section 1
         */
        private void refreshSuggestions() {
            SwingUtilities.invokeLater(() -> {
                SuggestionRanker ranker = suggestionRanker;
                suggestionModel.clear();
                if (ranker == null) {
                    return; // catalog still loading (or missing - journaled by the loader)
                }
                String query = searchField.getText();
                List<ConceptSuggestion> top = (query == null || query.isBlank())
                        ? ranker.suggestForElement(selectedName, selectedStereotypes, 5)
                        : ranker.search(query.trim(), selectedName, selectedStereotypes, 8);
                top.forEach(suggestionModel::addElement);
                if (!top.isEmpty()) {
                    DiagnosticLog.event("SUGGEST", selectedName
                            + " | query=" + (query == null || query.isBlank() ? "-" : query.trim())
                            + " | top=" + top.stream()
                                    .map(s -> s.entry().curie() + ":" + Math.round(s.score() * 100))
                                    .collect(Collectors.joining(", ")));
                }
            });
        }

        private void appendConsole(String message) {
            SwingUtilities.invokeLater(() -> consoleArea.append(message + "\n"));
        }

        private void showAuditRunning() {
            SwingUtilities.invokeLater(() -> {
                auditButton.setEnabled(false);
                statusBadge.setText("STATUS: AUDITING...");
                statusBadge.setBackground(BADGE_RUNNING);
            });
        }

        /**
         * @param consistent reasoner verdict, or null when the audit itself errored
         */
        private void showAuditResult(Boolean consistent, int violations, List<String> messages) {
            SwingUtilities.invokeLater(() -> {
                auditButton.setEnabled(true);
                if (consistent == null) {
                    statusBadge.setText("STATUS: AUDIT ERROR");
                    statusBadge.setBackground(BADGE_NEUTRAL);
                } else if (consistent && violations == 0) {
                    statusBadge.setText("STATUS: CONSISTENT");
                    statusBadge.setBackground(BADGE_OK);
                } else {
                    statusBadge.setText("STATUS: " + (violations > 0
                            ? violations + " VIOLATION(S)" : "INCONSISTENT"));
                    statusBadge.setBackground(BADGE_BAD);
                }
                for (String message : messages) {
                    consoleArea.append(message + "\n");
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
