package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.semantic.align.CatalogLoader;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.StereotypeRouter;
import com.nomagic.magicdraw.plugins.semantic.align.SuggestionRanker;
import com.nomagic.magicdraw.plugins.semantic.align.UafConceptResolver;
import com.nomagic.magicdraw.plugins.semantic.align.terms.AlignmentCandidate;
import com.nomagic.magicdraw.plugins.semantic.align.terms.CapabilityGuard;
import com.nomagic.magicdraw.plugins.semantic.align.terms.TermSource;
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
    // Automatic UAF-stereotype -> ontology-concept resolver (derived from uaf_ontology).
    private static volatile UafConceptResolver uafResolver;
    // OntoPortal keys are read from ~/.semantic_alignment_plugin/ontoportal.properties
    // (portal.apikey=…) so end users never edit Cameo JVM options; a -Dsemantic.plugin.
    // ontoportal.<portal>.<prop> system property overrides the file. See design/ontoportal_setup.md.
    // MUST be declared BEFORE ONLINE_SOURCES: buildOnlineSources() reads ONTOPORTAL_CONFIG during
    // static init and Java runs field initializers in TEXTUAL ORDER - declaring it after threw an
    // NPE in <clinit> that failed the whole plugin to load (owner: no startup exceptions).
    private static final java.util.Properties ONTOPORTAL_CONFIG = loadOntoPortalConfig();

    // OLS-family online term sources (same REST API shape). Default: EBI OLS4 (~280
    // life-science ontologies) + the TIB Terminology Service (100+ engineering/physics/
    // chemistry/materials ontologies - the systems-engineering breadth OLS4 lacks). Both
    // keyless, remote-reference (license-clean). Override the whole list with
    // -Dsemantic.plugin.ols.endpoints (comma-separated base URLs); each also honors
    // -Dsemantic.plugin.ols4.baseurl for a single air-gapped instance. Stateless + thread-safe.
    private static final java.util.List<com.nomagic.magicdraw.plugins.semantic.align.terms.TermSource>
            ONLINE_SOURCES = buildOnlineSources();

    private static java.util.List<com.nomagic.magicdraw.plugins.semantic.align.terms.TermSource>
            buildOnlineSources() {
        // Delegated to a Cameo-free, unit-tested factory that is null-safe and never throws, so
        // this static-field initializer cannot fail plugin load (regression: OnlineSourceFactory).
        return com.nomagic.magicdraw.plugins.semantic.align.terms.OnlineSourceFactory.build(ONTOPORTAL_CONFIG);
    }

    private static java.util.Properties loadOntoPortalConfig() {
        java.util.Properties props = new java.util.Properties();
        try {
            java.io.File f = DiagnosticLog.getLogDirectory().resolve("ontoportal.properties").toFile();
            if (f.isFile()) {
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    props.load(in);
                }
            }
        } catch (Throwable ignored) {
            // no config file is the normal (portals disabled) case
        }
        return props;
    }

    // Panel per open project so diagram-click routing reaches the right sidebar.
    // Weak keys: a closed project must not be pinned by its panel registration.
    private static final java.util.Map<Project, SemanticBrowserPanel> PANELS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
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
        // The Semantic Alignment Profile ships as a real SHARED module in the install
        // profiles dir (deployProfileAsset), where useModule resolves it. mandatory.profiles
        // normally auto-attaches it; this is the mount-on-first-use fallback path.
        try {
            StereotypeManager.setShippedProfile(new File(
                    com.nomagic.magicdraw.core.ApplicationEnvironment.getProfilesDirectory(),
                    "Semantic Alignment Profile.mdzip"));
        } catch (Throwable t) {
            log.error("Could not resolve install profiles directory", t);
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
        // "catalog loading/missing" hint until this completes. Includes the user
        // catalog dir for on-demand ontology imports (medical device / regulatory).
        Thread catalogLoader = new Thread(SemanticAlignmentPlugin::reloadCatalog,
                "semantic-catalog-loader");
        catalogLoader.setDaemon(true);
        catalogLoader.start();

        // UX click budgets are measured, not aspirational (v3 plan section 5)
        UxMetrics.install();

        // Diagram clicks must reach the sidebar too - the containment-tree listener
        // alone misses selections made ON a diagram (live finding: clicking an Action
        // in an activity diagram did nothing).
        installDiagramSelectionRelay();

        // Tools > Semantic Alignment submenu: explicit Instrument / Remove Instrumentation
        // (owner requirement - instrumenting a model is a deliberate, reversible action).
        try {
            com.nomagic.magicdraw.actions.ActionsConfiguratorsManager.getInstance()
                    .addMainMenuConfigurator(new SemanticMenuConfigurator());
            DiagnosticLog.event("LIFECYCLE", "Tools > Semantic Alignment menu configurator registered");
        } catch (Throwable t) {
            log.error("Could not register Tools menu configurator", t);
            DiagnosticLog.event("ERROR", "Menu configurator registration failed: " + t);
        }

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
        }, SemanticAlignmentPlugin::reloadCatalog);
        restService.start();

        // One panel per project browser: the panel captures its own project so that
        // selections, mappings, and audits always target the project they came from.
        Browser.addBrowserInitializer(new Browser.BrowserInitializer() {
            @Override
            public void init(Browser browser, Project project) {
                SemanticBrowserPanel panel = new SemanticBrowserPanel(project);
                PANELS.put(project, panel);
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

    /**
     * (Re)loads shipped + user catalogs and swaps the ranker atomically. Called at init
     * and from REST /catalog/reload, so on-demand ontology imports (drop a .ttl in the
     * user catalog dir) become alignable without restarting Cameo.
     *
     * @return summary string for the REST response
     */
    static String reloadCatalog() {
        CatalogLoader.LoadedCatalog catalog = CatalogLoader.loadMerged(pluginDirectory);
        catalogModel = catalog.model();
        suggestionRanker = new SuggestionRanker(catalog.index(), StereotypeRouter.load(pluginDirectory));
        // Automatic UAF-layer alignment: derived from the ontology's label/xmiID/subClassOf.
        uafResolver = UafConceptResolver.fromModel(catalog.model());
        DiagnosticLog.event("CATALOG", "UAF auto-match concepts: " + uafResolver.size());
        return "{\"concepts\":" + catalog.index().size()
                + ",\"tboxTriples\":" + catalog.model().size()
                + ",\"userCatalog\":\"" + CatalogLoader.resolveUserCatalogDirectory()
                        .getAbsolutePath().replace("\\", "\\\\") + "\"}";
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
     * Routes diagram selections into the sidebar. There is no public diagram-selection
     * listener API, so this is state-based: after any mouse click completes, read the
     * active diagram's selection through documented calls (Project.getActiveDiagram,
     * DiagramPresentationElement.getSelected via guarded reflection per the project's
     * introspection rule) and hand the element to the owning project's panel.
     */
    private static void installDiagramSelectionRelay() {
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event.getID() != java.awt.event.MouseEvent.MOUSE_CLICKED) {
                return;
            }
            // invokeLater: run AFTER the click's own selection processing has finished
            SwingUtilities.invokeLater(SemanticAlignmentPlugin::relayActiveDiagramSelection);
        }, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        DiagnosticLog.event("LIFECYCLE", "Diagram selection relay installed");
    }

    private static void relayActiveDiagramSelection() {
        if (relayBroken) {
            return;
        }
        try {
            Project project = com.nomagic.magicdraw.core.Application.getInstance().getProject();
            if (project == null || project.isProjectClosed() || project.isProjectDisposed()) {
                return;
            }
            SemanticBrowserPanel panel = PANELS.get(project);
            if (panel == null) {
                return;
            }
            Object diagram = project.getActiveDiagram();
            if (diagram == null) {
                return;
            }
            Object selected = diagram.getClass().getMethod("getSelected").invoke(diagram);
            if (!(selected instanceof java.util.List<?> list) || list.isEmpty()) {
                return;
            }
            Object first = list.get(0);
            Object element = first.getClass().getMethod("getElement").invoke(first);
            if (element instanceof Element modelElement
                    && modelElement != panel.selectedElement) {
                panel.handleSelection(modelElement);
            }
        } catch (NoSuchMethodException e) {
            // API drift on this Cameo version - journal once and stop trying
            DiagnosticLog.event("WARN", "Diagram selection relay disabled: " + e);
            relayBroken = true;
        } catch (Throwable t) {
            if (!relayBroken) {
                log.error("Diagram selection relay failed", t);
            }
        }
    }

    private static volatile boolean relayBroken;

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
        // Read-only, pinned base concept derived from the UAF stereotype (the intent of the
        // stereotype; never editable, never replaceable by a disjoint term). UC-1.3.
        private final JLabel baseConceptLabel = new JLabel("Base concept: -");
        private final JTextArea sbvrArea = new JTextArea("Select an element in the containment tree.");
        private final DefaultListModel<ConceptSuggestion> suggestionModel = new DefaultListModel<>();
        private final JList<ConceptSuggestion> suggestionList = new JList<>(suggestionModel);
        private final JLabel suggestStatus = new JLabel(" ");
        private final JButton applySelectedButton = new JButton("Apply Selected Concept(s)");
        private final JTextField searchField = new JTextField();
        private final JButton ols4Button = new JButton("Search Online Ontologies");
        private final JButton auditButton = new JButton("Run Audit");
        private final JLabel statusBadge = new JLabel("STATUS: NOT AUDITED");
        private final JTextArea consoleArea = new JTextArea();

        // Context the ranker scores against; updated on every tree selection.
        private volatile String selectedName = "";
        private volatile List<String> selectedStereotypes = List.of();
        // Auto-known UAF concept label the suggestions narrow FROM (null = free search).
        private volatile String narrowFrom;
        // The pinned base concept IRI (from the UAF stereotype), always written at index 0.
        private volatile String pinnedBaseURI;
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
            // Pinned, read-only base concept (the stereotype's intent). Rendered in the accent
            // colour with a lock hint so it is clearly not user-editable.
            baseConceptLabel.setName("semantic.baseConcept");
            baseConceptLabel.setForeground(FG_ACCENT);
            baseConceptLabel.setFont(baseConceptLabel.getFont().deriveFont(Font.BOLD, 11f));
            JPanel metaCard = new JPanel();
            metaCard.setLayout(new BoxLayout(metaCard, BoxLayout.Y_AXIS));
            metaCard.setBackground(BG_CARD);
            metaCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x334155)),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            metaCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            metaCard.add(selectionLabel);
            metaCard.add(typeLabel);
            metaCard.add(baseConceptLabel);
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

            // Suggested mappings: ranked for the selected element with zero keystrokes.
            // Ctrl/Shift-click to pick ONE OR MORE; double-click, Enter, or the button applies
            // them additively on top of the pinned base concept.
            JLabel suggestHeader = new JLabel("Suggested Mappings (Ctrl-click several; Enter / double-click applies)");
            suggestHeader.setForeground(FG_MUTED);
            suggestHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(suggestHeader);
            suggestionList.setName("semantic.suggestionList");
            suggestionList.setBackground(BG_CARD);
            suggestionList.setForeground(FG_MAIN);
            suggestionList.setSelectionBackground(new Color(0x0369a1));
            suggestionList.setSelectionForeground(Color.WHITE);
            suggestionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            suggestionList.setVisibleRowCount(4);
            suggestionList.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                        int idx, boolean sel, boolean focus) {
                    Component c = super.getListCellRendererComponent(list, value, idx, sel, focus);
                    if (value instanceof ConceptSuggestion s) {
                        // Multi-line HTML: label + curie + score + ontology, then a context
                        // snippet, then the SBVR sentence - so the user can choose informed.
                        String ctx = s.context() == null ? "" : htmlEscape(truncate(s.context(), 140));
                        String sbvr = s.sbvr() == null ? "" : htmlEscape(s.sbvr());
                        StringBuilder html = new StringBuilder("<html><div style='width:300px'>");
                        html.append("<b>").append(htmlEscape(s.entry().label())).append("</b> &nbsp; ")
                                .append(htmlEscape(s.entry().curie())).append(" &nbsp; ")
                                .append(Math.round(s.score() * 100)).append('%');
                        if (s.entry().ontologyId() != null && !s.entry().ontologyId().isBlank()) {
                            html.append(" &nbsp;<span style='color:#64748b'>[")
                                    .append(htmlEscape(s.entry().ontologyId())).append("]</span>");
                        }
                        if (!ctx.isEmpty()) {
                            html.append("<br><span style='color:#94a3b8'>").append(ctx).append("</span>");
                        }
                        if (!sbvr.isEmpty()) {
                            html.append("<br><span style='color:#64748b'>").append(sbvr).append("</span>");
                        }
                        html.append("</div></html>");
                        setText(html.toString());
                        // Width-capped, escaped tooltip so long ontology comments don't render
                        // as one very wide popup (owner report).
                        String tip = s.entry().comment() == null || s.entry().comment().isBlank()
                                ? s.entry().iri() : s.entry().comment();
                        setToolTipText("<html><div style='width:320px'>" + htmlEscape(tip) + "</div></html>");
                    }
                    return c;
                }
            });
            // Double-click applies the selection (single click just selects, per multi-select).
            suggestionList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        applySelectedSuggestions();
                    }
                }
            });
            // Enter on the list applies the selection too.
            suggestionList.getInputMap().put(
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "applySemantic");
            suggestionList.getActionMap().put("applySemantic", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    applySelectedSuggestions();
                }
            });
            JScrollPane suggestScroll = new JScrollPane(suggestionList);
            suggestScroll.setPreferredSize(new Dimension(280, 150));
            suggestScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(suggestScroll);
            suggestStatus.setName("semantic.suggestStatus");
            suggestStatus.setForeground(FG_MUTED);
            suggestStatus.setFont(suggestStatus.getFont().deriveFont(10f));
            suggestStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(suggestStatus);
            applySelectedButton.setName("semantic.applySelectedButton");
            applySelectedButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            applySelectedButton.addActionListener(e -> applySelectedSuggestions());
            root.add(applySelectedButton);
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
            root.add(Box.createVerticalStrut(4));
            // Explicit online search across the OLS-family sources (EBI OLS4 + TIB engineering
            // terminology) - one of MANY source families, queried only on click (considerate
            // use). Results append to the list, marked with their source ontology + a license
            // flag when restrictively licensed.
            ols4Button.setName("semantic.ols4Button");
            ols4Button.setAlignmentX(Component.LEFT_ALIGNMENT);
            ols4Button.setToolTipText("Search the online ontology services (EBI OLS4 + TIB "
                    + "engineering terminology) for the term above; results are appended");
            ols4Button.addActionListener(e -> searchOnline());
            root.add(ols4Button);
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

                // AUTOMATIC UAF-layer alignment: match the applied stereotype to its UAF
                // ontology concept by name/GUID - no user action (owner correction).
                UafConceptResolver.UafConcept auto = resolveUafConcept(stereotypes);
                String userConceptURI = readMappedConceptURI(element);

                String sbvr;
                if (userConceptURI != null) {
                    sbvr = SBVR_ENGINE.generatePlainSBVR(sbvrSubject, userConceptURI, null, null);
                } else if (auto != null) {
                    // Auto-known concept + its foundational (gUFO) equivalent, no clicks
                    sbvr = SBVR_ENGINE.generatePlainSBVR(sbvrSubject, auto.iri(), null, null)
                            + (auto.foundationalLabel() != null
                                    ? "  (foundational: " + auto.foundationalLabel() + ")" : "");
                } else {
                    sbvr = "No semantic alignment. Pick a concept below to align.";
                }
                DiagnosticLog.event("SELECTION", name + " | stereotypes=" + typeText
                        + " | autoConcept=" + (auto == null ? "-" : auto.label()
                                + " (" + (auto.foundationalLabel() == null ? "-" : auto.foundationalLabel()) + ")")
                        + " | userConcept=" + (userConceptURI == null ? "-" : userConceptURI)
                        + " | sbvr=" + sbvr);
                String autoText = auto == null ? typeText
                        : typeText + "  ->  " + auto.label()
                                + (auto.foundationalLabel() != null ? " / " + auto.foundationalLabel() : "");
                showSelection(name, autoText, sbvr);

                // Suggestions now NARROW from the auto-known concept (owner correction):
                // seed the ranker query with the UAF concept label so subclasses/domain
                // refinements surface first. Empty when no auto concept (free search).
                selectedName = sbvrSubject;
                selectedStereotypes = stereotypes.stream().map(Stereotype::getName).toList();
                narrowFrom = auto == null ? null : auto.label();
                // Pin the UAF-derived base concept: it is the intent of the stereotype, shown
                // read-only, always written at index 0, never replaceable by a disjoint term.
                pinnedBaseURI = auto == null ? null : auto.iri();
                final String baseText = auto == null
                        ? "Base concept: (none - free alignment)"
                        : "Base concept (locked): " + auto.label()
                                + (auto.foundationalLabel() != null ? "  /  " + auto.foundationalLabel() : "");
                SwingUtilities.invokeLater(() -> baseConceptLabel.setText(baseText));
                refreshSuggestions();
            } catch (Exception e) {
                log.error("Selection handling failed", e);
                DiagnosticLog.event("ERROR", "Selection handling failed: " + e);
            }
        }

        /**
         * Most specific UAF concept among the applied stereotypes. UAF elements often
         * carry several stereotypes; the resolver maps each and we prefer the one whose
         * concept is deepest (Actual* over generic) - here, simply the first that maps.
         */
        private UafConceptResolver.UafConcept resolveUafConcept(List<Stereotype> stereotypes) {
            UafConceptResolver resolver = uafResolver;
            if (resolver == null) {
                return null;
            }
            for (Stereotype stereotype : stereotypes) {
                UafConceptResolver.UafConcept concept =
                        resolver.resolve(stereotype.getName(), stereotype.getID());
                if (concept != null) {
                    return concept;
                }
            }
            return null;
        }

        /** The primary (index-0) stored concept, or null if the element is unaligned. */
        private String readMappedConceptURI(Element element) {
            List<String> all = StereotypeManager.getMappedConcepts(element);
            return all.isEmpty() ? null : all.get(0);
        }

        /** Single-concept apply (typed CURIE/IRI); additive - keeps the pinned base + prior concepts. */
        private void applyMappingFromUI(String conceptURI) {
            applyMappingsFromUI(java.util.List.of(conceptURI));
        }

        /**
         * Applies ONE OR MORE concepts to the selected element, ADDITIVELY. The pinned base
         * concept (the UAF-stereotype concept, if any) is always kept at index 0 and can never
         * be replaced by a disjoint term; the new picks are appended to whatever is already
         * stored. Runs the alive/editable/instrumented guards once, then a single write
         * transaction. Trace: design/use_cases.md UC-1.3, UC-2.2
         */
        private void applyMappingsFromUI(java.util.List<String> newConcepts) {
            Element element = selectedElement;
            if (element == null) {
                DiagnosticLog.event("MAPPING", "Rejected: no element selected");
                appendConsole("[FAIL] Select an element in the containment tree first.");
                return;
            }
            java.util.List<String> picks = new java.util.ArrayList<>();
            if (newConcepts != null) {
                for (String c : newConcepts) {
                    if (c != null && !c.isBlank()) {
                        picks.add(c.trim());
                    }
                }
            }
            if (picks.isEmpty() && (pinnedBaseURI == null || pinnedBaseURI.isBlank())) {
                appendConsole("[FAIL] Nothing to apply - select a suggestion or type a concept.");
                return;
            }
            String summary = String.join(", ", picks);
            try {
                if (project.isProjectClosed() || project.isProjectDisposed()
                        || project.isDisposed(element)) {
                    DiagnosticLog.event("MAPPING", "Rejected: project or element no longer alive");
                    appendConsole("[FAIL] The project or element is no longer open.");
                    return;
                }
                if (!element.isEditable()) {
                    DiagnosticLog.event("MAPPING", element.getHumanName()
                            + " -> " + summary + " | status=REJECTED read-only element");
                    appendConsole("[FAIL] Element is read-only (library or locked element).");
                    return;
                }
                // Alignment requires the model to be instrumented (explicit opt-in, owner
                // requirement). If it isn't, offer to instrument now rather than silently
                // mounting the profile behind the user's back.
                if (!StereotypeManager.isInstrumented(project)) {
                    int choice = JOptionPane.showConfirmDialog(
                            com.nomagic.magicdraw.core.Application.getInstance().getMainFrame(),
                            "This model is not yet instrumented for semantics.\n"
                                    + "Instrument it now (adds the Semantic Alignment profile\n"
                                    + "and a model-level ontology root IRI + version)?",
                            "Instrument Model",
                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (choice != JOptionPane.YES_OPTION) {
                        DiagnosticLog.event("MAPPING", "Rejected: model not instrumented (user declined)");
                        appendConsole("[FAIL] Model is not instrumented. "
                                + "Use Tools > Semantic Alignment > Instrument Model.");
                        return;
                    }
                    // Mount the shipped profile module - OUTSIDE the session.
                    if (!StereotypeManager.ensureProfileAvailable(project)) {
                        DiagnosticLog.event("MAPPING", "Rejected: Semantic Alignment Profile module unavailable");
                        appendConsole("[FAIL] Semantic Alignment Profile module could not be mounted.");
                        return;
                    }
                    final String rootIri = StereotypeManager.defaultRootIri(project);
                    TransactionWrapper.executeWrite(project, "Instrument Model for Semantics",
                            () -> StereotypeManager.applyModelInstrumentation(project, rootIri, "1.0.0",
                                    "semantic-alignment-plugin/" + VERSION));
                    DiagnosticLog.event("INSTRUMENT", project.getName()
                            + " (auto from align) rootIRI=" + rootIri);
                    appendConsole("[OK] Model instrumented (root IRI " + rootIri + ").");
                } else if (!StereotypeManager.ensureProfileAvailable(project)) {
                    // Instrumented models still need the profile module resolvable (e.g. a
                    // model instrumented in a prior session, reopened fresh).
                    DiagnosticLog.event("MAPPING", "Rejected: Semantic Alignment Profile module unavailable");
                    appendConsole("[FAIL] Semantic Alignment Profile module could not be mounted.");
                    return;
                }
                // Build the ordered concept list: pinned base first, then prior concepts, then
                // the new picks. setSemanticConcepts de-duplicates preserving order, so the
                // base stays at index 0 and never gets overwritten by a disjoint pick.
                final java.util.List<String> ordered = new java.util.ArrayList<>();
                if (pinnedBaseURI != null && !pinnedBaseURI.isBlank()) {
                    ordered.add(pinnedBaseURI);
                }
                ordered.addAll(StereotypeManager.getMappedConcepts(element));
                ordered.addAll(picks);
                Project owner = Project.getProject(element);
                TransactionWrapper.executeWrite(owner != null ? owner : project,
                        "Apply Semantic Mapping",
                        () -> StereotypeManager.setSemanticConcepts(element, ordered));
                DiagnosticLog.event("MAPPING", element.getHumanName() + " += " + summary
                        + " | base=" + (pinnedBaseURI == null ? "-" : pinnedBaseURI) + " | status=OK");
                appendConsole("[OK] Applied " + (picks.isEmpty() ? "base concept" : picks.size()
                        + " concept(s)") + (pinnedBaseURI == null ? "" : " (base locked)"));
                handleSelection(element); // refresh the sidebar with the new alignment
                maybeWarnCapabilityActivity(picks); // advisory capability/activity guard
            } catch (Exception e) {
                log.error("Semantic mapping failed", e);
                DiagnosticLog.event("MAPPING", element.getHumanName() + " -> " + summary
                        + " | status=FAILED | " + e.getMessage());
                appendConsole("[FAIL] " + e.getMessage());
            }
        }

        /** Applies the currently-selected suggestion rows, additively (base stays pinned). */
        private void applySelectedSuggestions() {
            java.util.List<ConceptSuggestion> picks = suggestionList.getSelectedValuesList();
            if (picks.isEmpty()) {
                appendConsole("[FAIL] Select one or more suggestions first (Ctrl-click for several).");
                return;
            }
            java.util.List<String> iris = new java.util.ArrayList<>();
            for (ConceptSuggestion s : picks) {
                iris.add(s.entry().iri());
            }
            DiagnosticLog.event("SUGGEST", "apply " + iris.size() + " selected for " + selectedName
                    + " : " + picks.stream().map(p -> p.entry().curie()).collect(Collectors.joining(", ")));
            applyMappingsFromUI(iris);
        }

        private static String htmlEscape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String truncate(String s, int max) {
            if (s == null) {
                return "";
            }
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }

        /**
         * Explicit online search across all OLS-family sources (EBI OLS4 + TIB engineering
         * terminology) for the current search-box text. Runs OFF the EDT (network), merges +
         * de-duplicates by IRI, then appends the candidates to the suggestion list, each marked
         * with its source ontology and a license flag. These are among MANY sources and are
         * queried only on this user action (considerate use). Trace: design/use_cases.md UC-2.1
         */
        private void searchOnline() {
            String raw = searchField.getText();
            if (raw == null || raw.isBlank()) {
                appendConsole("[..] Type a term in the search box first, then Search Online Ontologies.");
                return;
            }
            final String query = raw.trim();
            final String ontologyFilter = System.getProperty("semantic.plugin.ols4.ontologies");
            ols4Button.setEnabled(false);
            suggestStatus.setText("Searching online ontologies for \"" + query + "\"…");
            Thread worker = new Thread(() -> {
                // Merge across sources, de-dup by IRI (first source wins), so EBI + TIB overlap
                // collapses to one row per concept.
                java.util.LinkedHashMap<String, AlignmentCandidate> merged = new java.util.LinkedHashMap<>();
                for (TermSource src : ONLINE_SOURCES) {
                    try {
                        for (AlignmentCandidate c : src.search(query, ontologyFilter, 8)) {
                            if (c.iri() != null && !c.iri().isBlank()) {
                                merged.putIfAbsent(c.iri(), c);
                            }
                        }
                    } catch (Throwable ignored) {
                        // one source failing must not sink the rest
                    }
                }
                final List<AlignmentCandidate> results = new java.util.ArrayList<>(merged.values());
                SwingUtilities.invokeLater(() -> {
                    ols4Button.setEnabled(true);
                    if (results.isEmpty()) {
                        suggestStatus.setText("Online: no results for \"" + query + "\" (offline or no match).");
                        DiagnosticLog.event("TERMSOURCE", "online '" + query + "' -> 0 (offline or no match)");
                        return;
                    }
                    int added = 0;
                    for (AlignmentCandidate c : results) {
                        suggestionModel.addElement(ols4Suggestion(c));
                        added++;
                    }
                    suggestStatus.setText(added + " online result(s) appended — Ctrl-click + Apply Selected.");
                    DiagnosticLog.event("TERMSOURCE", "online '" + query + "' -> " + added + " from "
                            + ONLINE_SOURCES.size() + " source(s) : "
                            + results.stream().map(AlignmentCandidate::oboId)
                                    .filter(java.util.Objects::nonNull).collect(Collectors.joining(", ")));
                });
            }, "semantic-online-search");
            worker.setDaemon(true);
            worker.start();
        }

        /** Converts an OLS4 candidate into a suggestion row (source + license in the context). */
        private ConceptSuggestion ols4Suggestion(AlignmentCandidate c) {
            String curie = (c.oboId() != null && !c.oboId().isBlank()) ? c.oboId() : c.iri();
            String prefix = c.ontologyPrefix() == null ? "ols4" : c.ontologyPrefix();
            StringBuilder ctx = new StringBuilder("via OLS4 [").append(prefix).append(']');
            if (c.restrictivelyLicensed()) {
                ctx.append("  license: ").append(c.licenseNote());
            }
            if (c.description() != null && !c.description().isBlank()) {
                ctx.append("  -  ").append(c.description());
            }
            ConceptEntry entry = new ConceptEntry(c.iri(), curie,
                    c.label() == null ? curie : c.label(), List.of(),
                    c.description() == null ? "" : c.description(),
                    "OLS4:" + prefix, prefix, ConceptIndex.tokenize(c.label()));
            String subject = (selectedName == null || selectedName.isBlank()) ? entry.label() : selectedName;
            String sbvr = SBVR_ENGINE.generatePlainSBVR(subject, c.iri(), null, null);
            return new ConceptSuggestion(entry, 0.72, "OLS4", ctx.toString(), sbvr);
        }

        /**
         * Advisory capability/activity guard: when a Capability element is aligned to an OLS4
         * term that is actually an Activity (e.g. UAF Capability "Search" -> NCIT Search), warn
         * the user (notify, don't block). Runs off the EDT (an OLS4 lookup per IRI).
         * Trace: design/use_cases.md UC-2.3
         */
        private void maybeWarnCapabilityActivity(java.util.List<String> iris) {
            boolean isCapability = selectedStereotypes.stream()
                    .anyMatch(s -> s != null && s.equalsIgnoreCase("Capability"));
            if (!isCapability || iris == null || iris.isEmpty()) {
                return;
            }
            Thread worker = new Thread(() -> {
                for (String iri : iris) {
                    try {
                        java.util.Optional<AlignmentCandidate> opt = java.util.Optional.empty();
                        for (TermSource src : ONLINE_SOURCES) {
                            opt = src.lookup(iri);
                            if (opt.isPresent()) {
                                break;
                            }
                        }
                        if (opt.isEmpty()) {
                            continue;
                        }
                        java.util.Optional<String> warn = CapabilityGuard.validate(
                                CapabilityGuard.Slot.CAPABILITY, opt.get().semanticType());
                        if (warn.isPresent()) {
                            String msg = opt.get().label() + " (" + opt.get().oboId() + "):\n\n" + warn.get();
                            DiagnosticLog.event("GUARD", "capability/activity: " + iri
                                    + " semanticType=" + opt.get().semanticType());
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                    com.nomagic.magicdraw.core.Application.getInstance().getMainFrame(),
                                    msg, "Capability vs Activity", JOptionPane.WARNING_MESSAGE));
                        }
                    } catch (Throwable ignored) {
                        // advisory only - never disrupt the alignment
                    }
                }
            }, "semantic-capability-guard");
            worker.setDaemon(true);
            worker.start();
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
                String query = searchField.getText();
                boolean typed = query != null && !query.isBlank();
                if (ranker == null) {
                    suggestStatus.setText("Ontology catalog not loaded yet.");
                    DiagnosticLog.event("SUGGEST", "catalog not loaded (ranker null)");
                    return;
                }
                List<ConceptSuggestion> top;
                if (typed) {
                    // Free search: decompose the TYPED phrase into variants (no stereotype term
                    // forced in, so a bare "Weather" search stays a weather search).
                    top = ranker.searchVariants(query.trim(), List.of(), null, 12);
                } else {
                    // Element-driven: decompose the element name + stereotype term(s), seeded
                    // by the auto-resolved UAF concept - best-match-first across ontologies.
                    top = ranker.searchVariants(selectedName, selectedStereotypes, narrowFrom, 12);
                }
                // Attach an SBVR sentence to each DISPLAYED suggestion (top-N only, cheap).
                String subject = (selectedName == null || selectedName.isBlank()) ? null : selectedName;
                for (ConceptSuggestion s : top) {
                    String sbvr = SBVR_ENGINE.generatePlainSBVR(
                            subject == null ? s.entry().label() : subject, s.entry().iri(), null, null);
                    suggestionModel.addElement(s.withSbvr(sbvr));
                }
                // Always give visible feedback, especially on zero matches (owner report:
                // typing "Weather" produced no visible change when nothing matched).
                if (top.isEmpty()) {
                    suggestStatus.setText(typed
                            ? "No matching concepts for \"" + query.trim() + "\"."
                            : (subject == null ? "Select an element, or type to search."
                                    : "No suggestions for \"" + subject + "\"."));
                } else {
                    suggestStatus.setText(top.size()
                            + " suggestion(s) — Ctrl-click several, Enter/double-click to apply.");
                }
                DiagnosticLog.event("SUGGEST", (subject == null ? "-" : subject)
                        + " | query=" + (typed ? query.trim() : "-")
                        + " | count=" + top.size()
                        + " | top=" + (top.isEmpty() ? "-" : top.stream()
                                .map(s -> s.entry().curie() + ":" + Math.round(s.score() * 100))
                                .collect(Collectors.joining(", "))));
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
