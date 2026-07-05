package com.nomagic.magicdraw.plugins.semantic.view;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;
import com.nomagic.magicdraw.plugins.semantic.SemanticRDFExporter;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.WindowComponent;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;
import com.nomagic.ui.ExtendedPanel;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Per-project "Semantic Ontology" browser window. The DEFAULT tab is SBVR Structured
 * English (owner requirement: 90% of users are Muggles - plain English leads); Turtle
 * and SPARQL are the expert tabs. Nothing renders eagerly: every view builds on demand,
 * off the EDT (model snapshot via invokeAndWait), and is capped so huge models cannot
 * freeze or flood the display - the header shows "N of M" and Save writes the full file.
 * Trace: v3 plan sections 2-3
 */
public final class OntologyViewPanel extends ExtendedPanel implements WindowComponent {

    private static final Logger log = Logger.getLogger(OntologyViewPanel.class);
    private static final int MAX_SBVR_SENTENCES = 500;
    private static final int MAX_TURTLE_CHARS = 100_000;

    public static final String[][] CANNED_QUERIES = {
            {"1. Alignment coverage (semantic gaps)", """
                SELECT ?element ?label WHERE {
                  ?element a ?type ; <http://www.w3.org/2000/01/rdf-schema#label> ?label .
                  FILTER(STRSTARTS(STR(?element), "http://purl.org/uaf/project/"))
                }"""},
            {"2. Capability without performer (Hoth gap)", """
                PREFIX uaf: <http://purl.org/uaf/ontology#>
                SELECT ?capability WHERE {
                  ?capability a uaf:Capability .
                  FILTER NOT EXISTS { ?performer ?exhibits ?capability .
                                      ?performer a uaf:ResourcePerformer }
                }"""},
            {"3. Org rollup (needs inference)", """
                PREFIX org: <http://www.w3.org/ns/org#>
                SELECT ?post ?org WHERE {
                  ?post a org:Post .
                  OPTIONAL { ?post (org:postIn|org:unitOf)+ ?org }
                }"""},
            {"4. Battery taxonomy (inference shows subclasses)", """
                PREFIX battinfo: <https://w3id.org/emmo/domain/battery#>
                SELECT ?cell WHERE { ?cell a battinfo:BatteryCell }"""},
            {"5. UAF 1.3 <-> UAFSML 2.0 equivalence", """
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                SELECT ?uaf13 ?uafsml20 WHERE {
                  { ?uaf13 owl:equivalentClass ?uafsml20 }
                  UNION { ?uafsml20 owl:equivalentClass ?uaf13 }
                  FILTER(STRSTARTS(STR(?uaf13), "http://purl.org/uaf/ontology#"))
                }"""},
    };

    private final Project project;
    private final Supplier<Model> catalogModelSupplier;
    private final WindowComponentInfo info;
    private final SbvrRenderer sbvrRenderer = new SbvrRenderer();

    private final JLabel sbvrStatus = new JLabel("Click Refresh to render the ontology as English.");
    private final JTextArea sbvrText = new JTextArea();
    private final JLabel turtleStatus = new JLabel("Click Refresh to serialize the ontology.");
    private final JTextArea turtleText = new JTextArea();
    private final JComboBox<String> cannedBox = new JComboBox<>();
    private final JTextArea queryText = new JTextArea(CANNED_QUERIES[0][1]);
    private final JCheckBox inferenceBox = new JCheckBox("with inference (OWL-micro)");
    private final DefaultTableModel resultsModel = new DefaultTableModel();
    private final JLabel queryStatus = new JLabel(" ");

    public OntologyViewPanel(Project project, Supplier<Model> catalogModelSupplier,
                             WindowComponentInfo info) {
        super(new BorderLayout());
        this.project = project;
        this.catalogModelSupplier = catalogModelSupplier;
        this.info = info;
        setName("semantic.ontologyView");

        JTabbedPane tabs = new JTabbedPane();
        tabs.setName("semantic.ontologyTabs");
        tabs.addTab("English (SBVR)", buildSbvrTab());
        tabs.addTab("Turtle", buildTurtleTab());
        tabs.addTab("SPARQL", buildSparqlTab());
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel buildSbvrTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JButton refresh = new JButton("Refresh");
        refresh.setName("semantic.sbvrRefresh");
        JButton save = new JButton("Save full…");
        save.setName("semantic.sbvrSave");
        sbvrText.setName("semantic.sbvrView");
        sbvrText.setEditable(false);
        sbvrText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sbvrText.setForeground(new Color(0x0369a1));

        refresh.addActionListener(e -> renderAsync(refresh, () -> {
            Model model = snapshotProjectModel();
            SbvrRenderer.Rendering rendering = sbvrRenderer.render(model, MAX_SBVR_SENTENCES);
            String body = String.join("\n", rendering.sentences());
            SwingUtilities.invokeLater(() -> {
                sbvrText.setText(body.isEmpty()
                        ? "No aligned facts yet. Map elements in the Semantic Alignment sidebar first."
                        : body);
                sbvrText.setCaretPosition(0);
                sbvrStatus.setText("Showing " + rendering.sentences().size()
                        + " of " + rendering.totalFacts() + " facts.");
            });
            DiagnosticLog.event("VIEW", "SBVR rendered: " + rendering.sentences().size()
                    + "/" + rendering.totalFacts() + " facts");
        }));
        save.addActionListener(e -> renderAsync(save, () -> {
            Model model = snapshotProjectModel();
            SbvrRenderer.Rendering rendering = sbvrRenderer.render(model, Integer.MAX_VALUE);
            Path file = DiagnosticLog.getLogDirectory().resolve("ontology-sbvr.txt");
            Files.write(file, rendering.sentences(), StandardCharsets.UTF_8);
            SwingUtilities.invokeLater(() -> sbvrStatus.setText("Saved " + rendering.totalFacts()
                    + " facts to " + file));
        }));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.add(refresh);
        top.add(Box.createHorizontalStrut(6));
        top.add(save);
        top.add(Box.createHorizontalStrut(10));
        top.add(sbvrStatus);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(sbvrText), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTurtleTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JButton refresh = new JButton("Refresh");
        refresh.setName("semantic.turtleRefresh");
        turtleText.setName("semantic.turtleView");
        turtleText.setEditable(false);
        turtleText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        refresh.addActionListener(e -> renderAsync(refresh, () -> {
            Model model = snapshotProjectModel();
            StringWriter writer = new StringWriter();
            model.write(writer, "TURTLE");
            String full = writer.toString();
            Path file = DiagnosticLog.getLogDirectory().resolve("ontology-export.ttl");
            Files.writeString(file, full, StandardCharsets.UTF_8);
            String shown = full.length() > MAX_TURTLE_CHARS
                    ? full.substring(0, MAX_TURTLE_CHARS) + "\n# … truncated for display …"
                    : full;
            SwingUtilities.invokeLater(() -> {
                turtleText.setText(shown);
                turtleText.setCaretPosition(0);
                turtleStatus.setText("Showing " + Math.min(full.length(), MAX_TURTLE_CHARS)
                        + " of " + full.length() + " chars (" + model.size()
                        + " triples). Full file: " + file);
            });
        }));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.add(refresh);
        top.add(Box.createHorizontalStrut(10));
        top.add(turtleStatus);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(turtleText), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSparqlTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        cannedBox.setName("semantic.cannedQueries");
        for (String[] canned : CANNED_QUERIES) {
            cannedBox.addItem(canned[0]);
        }
        cannedBox.addActionListener(e -> {
            int idx = cannedBox.getSelectedIndex();
            if (idx >= 0) {
                queryText.setText(CANNED_QUERIES[idx][1]);
            }
        });
        queryText.setName("semantic.sparqlQuery");
        queryText.setRows(7);
        queryText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inferenceBox.setName("semantic.sparqlInference");
        JButton run = new JButton("Run");
        run.setName("semantic.sparqlRun");
        JTable results = new JTable(resultsModel);
        results.setName("semantic.sparqlResults");
        queryStatus.setName("semantic.sparqlStatus");

        run.addActionListener(e -> renderAsync(run, () -> {
            long start = System.currentTimeMillis();
            Model dataset = ModelFactory.createUnion(
                    catalogModelSupplier.get() == null
                            ? ModelFactory.createDefaultModel() : catalogModelSupplier.get(),
                    snapshotProjectModel());
            if (inferenceBox.isSelected()) {
                dataset = ModelFactory.createInfModel(
                        ReasonerRegistry.getOWLMicroReasoner(), dataset);
            }
            Query query = QueryFactory.create(queryText.getText());
            List<String> columns = new ArrayList<>();
            List<String[]> rows = new ArrayList<>();
            try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                ResultSet resultSet = execution.execSelect();
                columns.addAll(resultSet.getResultVars());
                while (resultSet.hasNext()) {
                    QuerySolution solution = resultSet.next();
                    String[] row = new String[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        row[i] = solution.contains(columns.get(i))
                                ? solution.get(columns.get(i)).toString() : "";
                    }
                    rows.add(row);
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            SwingUtilities.invokeLater(() -> {
                resultsModel.setDataVector(rows.toArray(new String[0][]),
                        columns.toArray(new String[0]));
                queryStatus.setText(rows.size() + " rows in " + elapsed + " ms"
                        + (inferenceBox.isSelected() ? " (inferred)" : ""));
            });
            DiagnosticLog.event("QUERY", "SPARQL rows=" + rows.size() + " ms=" + elapsed
                    + " inference=" + inferenceBox.isSelected());
        }));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(cannedBox);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(inferenceBox);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(run);
        controls.add(Box.createHorizontalStrut(10));
        controls.add(queryStatus);
        top.add(controls, BorderLayout.NORTH);
        top.add(new JScrollPane(queryText), BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(results), BorderLayout.CENTER);
        return panel;
    }

    /** Snapshot the project as RDF on the EDT (model access is single-threaded). */
    private Model snapshotProjectModel() throws Exception {
        final Model[] holder = new Model[1];
        final Exception[] error = new Exception[1];
        Runnable task = () -> {
            try {
                holder[0] = new SemanticRDFExporter(project).exportToModel();
            } catch (Exception ex) {
                error[0] = ex;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
        if (error[0] != null) {
            throw error[0];
        }
        return holder[0];
    }

    private interface Work {
        void run() throws Exception;
    }

    /** Runs view work on a daemon worker with the trigger button disabled meanwhile. */
    private void renderAsync(JButton trigger, Work work) {
        trigger.setEnabled(false);
        Thread worker = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                log.error("Ontology view action failed", t);
                DiagnosticLog.event("ERROR", "Ontology view action failed: " + t);
                SwingUtilities.invokeLater(() ->
                        queryStatus.setText("Error: " + t.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> trigger.setEnabled(true));
            }
        }, "semantic-ontology-view");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public WindowComponentInfo getInfo() {
        return info;
    }

    @Override
    public WindowComponentContent getContent() {
        return new WindowComponentContent() {
            @Override
            public Component getWindowComponent() {
                return OntologyViewPanel.this;
            }

            @Override
            public Component getDefaultFocusComponent() {
                return OntologyViewPanel.this;
            }
        };
    }
}
