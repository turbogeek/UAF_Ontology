package com.nomagic.magicdraw.plugins.semantic.ui;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import com.nomagic.magicdraw.plugins.semantic.align.CompoundConcept;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Compose Concept" is a full concept COMPOSER (owner design): SEARCH ontologies (local + online),
 * see what each concept MEANS (its definition), ADD concepts to the compound as the genus ("is a
 * kind of") or as a differentia (a relation applied to a qualifier), ARRANGE/remove them, and
 * watch the compound's SBVR build LIVE ("A Mosquito Killing Drone is a Drone that kills a
 * Mosquito"). On Apply it returns the encoded {@code mappedConceptURI} values.
 *
 * <p>Pure Swing (Cameo has no JavaFX). All controls carry {@code semantic.compose.*} names for
 * GUI tests. Search runs OFF the EDT via {@link ConceptSearch}. Trace: design/compound_concepts.md</p>
 */
public final class ComposeConceptDialog extends JDialog {

    /** A concept the user can search for, inspect, and add. */
    public record ConceptRef(String iri, String label, String ontology, String definition) {
        public ConceptRef(String iri, String label) {
            this(iri, label, "", "");
        }
        @Override
        public String toString() {
            return label + (ontology == null || ontology.isBlank() ? "" : "   [" + ontology + "]");
        }
    }

    /** Ontology search the dialog calls (local + optional online); implemented by the plugin. */
    public interface ConceptSearch {
        List<ConceptRef> search(String query, boolean online);
    }

    /** One differentia being built: a relation phrase applied to a qualifier concept. */
    private static final class DiffRow {
        final ConceptRef concept;
        final JComboBox<String> relation;
        final JPanel panel;
        DiffRow(ConceptRef concept, JComboBox<String> relation, JPanel panel) {
            this.concept = concept;
            this.relation = relation;
            this.panel = panel;
        }
    }

    private final SBVREngine sbvr = new SBVREngine();
    private final List<String> relationPhrases;
    private final ConceptSearch search;

    private final JTextField nameField = new JTextField(22);
    private final JTextField searchField = new JTextField(18);
    private final JCheckBox onlineCheck = new JCheckBox("online");
    private final DefaultListModel<ConceptRef> resultsModel = new DefaultListModel<>();
    private final JList<ConceptRef> resultsList = new JList<>(resultsModel);
    private final JTextArea conceptSbvr = new JTextArea(4, 28);

    private ConceptRef genus;
    private final JLabel genusLabel = new JLabel("(none - add a genus)");
    private final JPanel diffPanel = new JPanel();
    private final List<DiffRow> diffRows = new ArrayList<>();
    private final JTextArea compoundSbvr = new JTextArea(4, 30);

    public ComposeConceptDialog(Frame owner, String elementName, ConceptRef initialGenus,
                                List<ConceptRef> initialDifferentia, List<String> relationPhrases,
                                ConceptSearch search, Consumer<List<String>> onApply) {
        super(owner, "Compose Concept", true);
        this.relationPhrases = relationPhrases == null ? List.of() : relationPhrases;
        this.search = search;
        this.genus = initialGenus;
        setName("semantic.composeDialog");

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(buildHeader(elementName), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSearchPane(), buildBuilderPane());
        split.setResizeWeight(0.5);
        split.setDividerLocation(420);
        content.add(split, BorderLayout.CENTER);
        content.add(buildButtons(onApply), BorderLayout.SOUTH);

        if (initialDifferentia != null) {
            for (ConceptRef d : initialDifferentia) {
                addDifferentia(d);
            }
        }
        refreshGenus();
        updateCompoundSbvr();
        setContentPane(content);
        setPreferredSize(new Dimension(900, 560));
        pack();
        if (owner != null) {
            setLocationRelativeTo(owner);
        }
    }

    private JPanel buildHeader(String elementName) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        header.add(new JLabel("New concept name:"));
        nameField.setName("semantic.compose.nameField");
        nameField.setText(elementName == null ? "" : elementName);
        nameField.getDocument().addDocumentListener(new SimpleDocListener(this::updateCompoundSbvr));
        header.add(nameField);
        header.add(new JLabel("     is a kind of "));
        genusLabel.setName("semantic.compose.genusLabel");
        header.add(genusLabel);
        return header;
    }

    private JPanel buildSearchPane() {
        JPanel pane = new JPanel(new BorderLayout(4, 4));
        pane.setBorder(BorderFactory.createTitledBorder("1. Search ontologies for concepts"));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        searchField.setName("semantic.compose.searchField");
        searchField.addActionListener(e -> doSearch());
        JButton searchBtn = new JButton("Search");
        searchBtn.setName("semantic.compose.searchButton");
        searchBtn.addActionListener(e -> doSearch());
        onlineCheck.setName("semantic.compose.onlineCheck");
        top.add(searchField);
        top.add(searchBtn);
        top.add(onlineCheck);
        pane.add(top, BorderLayout.NORTH);

        resultsList.setName("semantic.compose.resultsList");
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.addListSelectionListener(e -> showConceptMeaning());
        JScrollPane resultsScroll = new JScrollPane(resultsList);
        resultsScroll.setPreferredSize(new Dimension(400, 220));
        pane.add(resultsScroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        conceptSbvr.setName("semantic.compose.conceptSbvr");
        conceptSbvr.setEditable(false);
        conceptSbvr.setLineWrap(true);
        conceptSbvr.setWrapStyleWord(true);
        JScrollPane cs = new JScrollPane(conceptSbvr);
        cs.setBorder(BorderFactory.createTitledBorder("What the selected concept means"));
        south.add(cs, BorderLayout.CENTER);
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton asGenus = new JButton("Set as genus (is a kind of)");
        asGenus.setName("semantic.compose.addGenusButton");
        asGenus.addActionListener(e -> setGenusFromSelection());
        JButton asDiff = new JButton("Add as → relation");
        asDiff.setName("semantic.compose.addDiffButton");
        asDiff.addActionListener(e -> addDifferentiaFromSelection());
        addRow.add(asGenus);
        addRow.add(asDiff);
        south.add(addRow, BorderLayout.SOUTH);
        pane.add(south, BorderLayout.SOUTH);
        return pane;
    }

    private JPanel buildBuilderPane() {
        JPanel pane = new JPanel(new BorderLayout(4, 4));
        pane.setBorder(BorderFactory.createTitledBorder("2. Compose - genus + relations"));
        diffPanel.setName("semantic.compose.diffPanel");
        diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
        JScrollPane diffScroll = new JScrollPane(diffPanel);
        diffScroll.setPreferredSize(new Dimension(420, 240));
        pane.add(diffScroll, BorderLayout.CENTER);

        compoundSbvr.setName("semantic.compose.sbvrPreview");
        compoundSbvr.setEditable(false);
        compoundSbvr.setLineWrap(true);
        compoundSbvr.setWrapStyleWord(true);
        JScrollPane ps = new JScrollPane(compoundSbvr);
        ps.setBorder(BorderFactory.createTitledBorder("SBVR of the concept you are creating"));
        pane.add(ps, BorderLayout.SOUTH);
        return pane;
    }

    private JPanel buildButtons(Consumer<List<String>> onApply) {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.setName("semantic.compose.cancelButton");
        cancel.addActionListener(e -> dispose());
        JButton apply = new JButton("Apply Compound Concept");
        apply.setName("semantic.compose.applyButton");
        apply.addActionListener(e -> {
            if (onApply != null && genus != null) {
                onApply.accept(encodedClauses());
            }
            dispose();
        });
        buttons.add(cancel);
        buttons.add(apply);
        return buttons;
    }

    // --- search -----------------------------------------------------------------------------

    private void doSearch() {
        final String q = searchField.getText();
        if (q == null || q.isBlank() || search == null) {
            return;
        }
        final boolean online = onlineCheck.isSelected();
        resultsModel.clear();
        resultsModel.addElement(new ConceptRef("", "searching…", "", ""));
        Thread worker = new Thread(() -> {
            List<ConceptRef> hits;
            try {
                hits = search.search(q.trim(), online);
            } catch (Throwable t) {
                hits = List.of();
            }
            final List<ConceptRef> results = hits;
            SwingUtilities.invokeLater(() -> {
                resultsModel.clear();
                if (results.isEmpty()) {
                    resultsModel.addElement(new ConceptRef("", "(no matches for \"" + q.trim() + "\")", "", ""));
                } else {
                    for (ConceptRef r : results) {
                        resultsModel.addElement(r);
                    }
                }
            });
        }, "compose-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void showConceptMeaning() {
        ConceptRef r = resultsList.getSelectedValue();
        if (r == null || r.iri() == null || r.iri().isBlank()) {
            conceptSbvr.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.label());
        if (r.ontology() != null && !r.ontology().isBlank()) {
            sb.append("  — ").append(r.ontology());
        }
        sb.append('\n');
        if (r.definition() != null && !r.definition().isBlank()) {
            sb.append(r.definition());
        } else {
            sb.append("A ").append(sbvr.getLocalName(r.iri())).append(" (no stored definition).");
        }
        conceptSbvr.setText(sb.toString());
        conceptSbvr.setCaretPosition(0);
    }

    // --- build ------------------------------------------------------------------------------

    private void setGenusFromSelection() {
        ConceptRef r = selectedResult();
        if (r != null) {
            genus = r;
            refreshGenus();
            updateCompoundSbvr();
        }
    }

    private void addDifferentiaFromSelection() {
        ConceptRef r = selectedResult();
        if (r != null) {
            addDifferentia(r);
            updateCompoundSbvr();
        }
    }

    private ConceptRef selectedResult() {
        ConceptRef r = resultsList.getSelectedValue();
        return (r == null || r.iri() == null || r.iri().isBlank()) ? null : r;
    }

    private void addDifferentia(ConceptRef concept) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.add(new JLabel("that"));
        JComboBox<String> rel = new JComboBox<>(relationPhrases.toArray(new String[0]));
        rel.setEditable(true);
        rel.setName("semantic.compose.relation." + diffRows.size());
        rel.addActionListener(e -> updateCompoundSbvr());
        row.add(rel);
        JLabel lbl = new JLabel(concept.label());
        row.add(lbl);
        JButton remove = new JButton("✕");
        remove.setToolTipText("Remove this relation");
        DiffRow dr = new DiffRow(concept, rel, row);
        remove.addActionListener(e -> {
            diffRows.remove(dr);
            diffPanel.remove(row);
            diffPanel.revalidate();
            diffPanel.repaint();
            updateCompoundSbvr();
        });
        row.add(remove);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffRows.add(dr);
        diffPanel.add(row);
        diffPanel.revalidate();
        diffPanel.repaint();
    }

    private void refreshGenus() {
        genusLabel.setText(genus == null ? "(none - add a genus)" : genus.label());
    }

    private CompoundConcept currentCompound() {
        List<CompoundConcept.Clause> diff = new ArrayList<>();
        for (DiffRow dr : diffRows) {
            String rel = String.valueOf(dr.relation.getEditor().getItem()).trim();
            diff.add(new CompoundConcept.Clause(rel, dr.concept.iri(), dr.concept.label()));
        }
        String label = nameField.getText() == null || nameField.getText().isBlank()
                ? "Concept" : nameField.getText().trim();
        return new CompoundConcept(label, genus == null ? null : genus.iri(), diff);
    }

    /** Encoded mappedConceptURI values (genus first, then relation|IRI|label clauses). */
    public List<String> encodedClauses() {
        return currentCompound().toStoredList();
    }

    /** The compound SBVR for the current build (exposed for tests). */
    public String previewSbvr() {
        return currentCompound().toSbvr(sbvr);
    }

    private void updateCompoundSbvr() {
        if (genus == null) {
            compoundSbvr.setText("Add a genus (\"is a kind of\") to begin.");
            return;
        }
        compoundSbvr.setText(previewSbvr());
        compoundSbvr.setCaretPosition(0);
    }

    /** Minimal DocumentListener that runs one callback on any change. */
    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable onChange;
        SimpleDocListener(Runnable onChange) {
            this.onChange = onChange;
        }
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
    }
}
