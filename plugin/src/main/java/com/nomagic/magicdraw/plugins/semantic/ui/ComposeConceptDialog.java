package com.nomagic.magicdraw.plugins.semantic.ui;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import com.nomagic.magicdraw.plugins.semantic.align.CompoundConcept;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "Compose Concept" is a full concept COMPOSER (owner design): SEARCH ontologies (local + online,
 * online on by default), see what each concept MEANS, ADD concepts in ANY order as the genus
 * ("is a kind of") or as a relation (differentia), REORDER them and promote any to genus, CREATE
 * NEW concepts (a drone isn't in any ontology - define it as a local overlay concept) and ADD NEW
 * relation verbs, all while the compound's SBVR builds LIVE. Apply returns the encoded
 * {@code mappedConceptURI} values.
 *
 * <p>Pure Swing (Cameo has no JavaFX); {@code semantic.compose.*} names for GUI tests; search runs
 * off the EDT via {@link ConceptSearch}. Trace: design/compound_concepts.md,
 * design/how-to-mosquito-killing-drone.md</p>
 */
public final class ComposeConceptDialog extends JDialog {

    /** A concept the user can search for, inspect, add, or newly create. */
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
    private final List<String> relationPhrases;         // mutable - the user can add verbs
    private final ConceptSearch search;
    private final String localNamespace;                // for newly-created local concepts
    private int relSeq = 0;                              // stable relation-combo naming

    private final JTextField nameField = new JTextField(22);
    private final JTextField searchField = new JTextField(18);
    private final JCheckBox onlineCheck = new JCheckBox("online", true); // default ON (owner)
    private final DefaultListModel<ConceptRef> resultsModel = new DefaultListModel<>();
    private final JList<ConceptRef> resultsList = new JList<>(resultsModel);
    private final JTextArea conceptSbvr = new JTextArea(4, 28);

    private ConceptRef genus;
    private final JLabel genusLabel = new JLabel("(none - add a genus)");
    private final JPanel diffPanel = new JPanel();
    private final List<DiffRow> diffRows = new ArrayList<>();
    private final JTextArea compoundSbvr = new JTextArea(5, 30);

    public ComposeConceptDialog(Frame owner, String elementName, ConceptRef initialGenus,
                                List<ConceptRef> initialDifferentia, List<String> relationPhrases,
                                String localNamespace, ConceptSearch search,
                                Consumer<List<String>> onApply) {
        super(owner, "Compose Concept", true);
        this.relationPhrases = new ArrayList<>(relationPhrases == null ? List.of() : relationPhrases);
        this.search = search;
        this.localNamespace = (localNamespace == null || localNamespace.isBlank())
                ? "http://purl.org/uaf/ontology#" : localNamespace;
        this.genus = initialGenus;
        setName("semantic.composeDialog");

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(buildHeader(elementName), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSearchPane(), buildBuilderPane());
        split.setResizeWeight(0.5);
        split.setDividerLocation(430);
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
        setPreferredSize(new Dimension(920, 580));
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
        JButton clearGenus = new JButton("clear");
        clearGenus.setName("semantic.compose.clearGenusButton");
        clearGenus.addActionListener(e -> {
            genus = null;
            refreshGenus();
            updateCompoundSbvr();
        });
        header.add(clearGenus);
        return header;
    }

    private JPanel buildSearchPane() {
        JPanel pane = new JPanel(new BorderLayout(4, 4));
        pane.setBorder(BorderFactory.createTitledBorder("Find or create concepts"));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        searchField.setName("semantic.compose.searchField");
        searchField.addActionListener(e -> doSearch());
        JButton searchBtn = new JButton("Search");
        searchBtn.setName("semantic.compose.searchButton");
        searchBtn.addActionListener(e -> doSearch());
        onlineCheck.setName("semantic.compose.onlineCheck");
        JButton newConcept = new JButton("New concept…");
        newConcept.setName("semantic.compose.newConceptButton");
        newConcept.setToolTipText("Define a concept that isn't in any ontology (a local overlay concept).");
        newConcept.addActionListener(e -> createNewConcept());
        top.add(searchField);
        top.add(searchBtn);
        top.add(onlineCheck);
        top.add(newConcept);
        pane.add(top, BorderLayout.NORTH);

        resultsList.setName("semantic.compose.resultsList");
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.addListSelectionListener(e -> showConceptMeaning());
        JScrollPane resultsScroll = new JScrollPane(resultsList);
        resultsScroll.setPreferredSize(new Dimension(400, 210));
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
        pane.setBorder(BorderFactory.createTitledBorder("Compose (add in any order, reorder, promote)"));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton newRel = new JButton("New relation…");
        newRel.setName("semantic.compose.newRelationButton");
        newRel.setToolTipText("Add a relation verb (e.g. \"kills\") to the dropdown list.");
        newRel.addActionListener(e -> createNewRelation());
        tools.add(newRel);
        pane.add(tools, BorderLayout.NORTH);

        diffPanel.setName("semantic.compose.diffPanel");
        diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
        JScrollPane diffScroll = new JScrollPane(diffPanel);
        diffScroll.setPreferredSize(new Dimension(430, 230));
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
            if (genus == null) {
                JOptionPane.showMessageDialog(this, "Set a genus (\"is a kind of\") first - select a "
                        + "concept and click \"Set as genus\", or promote a relation row with its "
                        + "\"genus\" button.", "Compose Concept", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (onApply != null) {
                onApply.accept(encodedClauses());
            }
            dispose();
        });
        buttons.add(cancel);
        buttons.add(apply);
        return buttons;
    }

    // --- search / create --------------------------------------------------------------------

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
                    resultsModel.addElement(new ConceptRef("", "(no matches - use \"New concept…\")", "", ""));
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

    /** Define a concept that isn't in any ontology - a local overlay concept in this model. */
    private void createNewConcept() {
        String name = JOptionPane.showInputDialog(this,
                "Name of the new concept (e.g. Drone):", "New concept", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        String def = JOptionPane.showInputDialog(this,
                "Optional short definition of \"" + name.trim() + "\":", "New concept",
                JOptionPane.PLAIN_MESSAGE);
        ConceptRef ref = new ConceptRef(localNamespace + camelCase(name), name.trim(), "new (this model)",
                def == null ? "" : def.trim());
        resultsModel.add(0, ref);
        resultsList.setSelectedIndex(0);
        showConceptMeaning();
    }

    /** Add a relation verb to the dropdown list (and every existing row's dropdown). */
    private void createNewRelation() {
        String phrase = JOptionPane.showInputDialog(this,
                "New relation verb phrase (e.g. kills, suppresses):", "New relation",
                JOptionPane.PLAIN_MESSAGE);
        if (phrase == null || phrase.isBlank()) {
            return;
        }
        String p = phrase.trim();
        if (!relationPhrases.contains(p)) {
            relationPhrases.add(0, p);
            for (DiffRow dr : diffRows) {
                ((DefaultComboBoxModel<String>) dr.relation.getModel()).insertElementAt(p, 0);
            }
        }
    }

    private void showConceptMeaning() {
        ConceptRef r = resultsList.getSelectedValue();
        if (r == null || r.iri() == null || r.iri().isBlank()) {
            conceptSbvr.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(r.label());
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

    // --- build / arrange --------------------------------------------------------------------

    private void setGenusFromSelection() {
        ConceptRef r = selectedResult();
        if (r != null) {
            setGenus(r);
        }
    }

    private void setGenus(ConceptRef r) {
        genus = r;
        refreshGenus();
        updateCompoundSbvr();
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
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        row.add(new JLabel("that"));
        JComboBox<String> rel = new JComboBox<>(new DefaultComboBoxModel<>(relationPhrases.toArray(new String[0])));
        rel.setEditable(true);
        rel.setName("semantic.compose.relation." + (relSeq++));
        rel.addActionListener(e -> updateCompoundSbvr());
        row.add(rel);
        row.add(new JLabel(concept.label()));
        DiffRow dr = new DiffRow(concept, rel, row);
        row.add(iconButton("↑", "Move up", () -> moveDiff(dr, -1)));
        row.add(iconButton("↓", "Move down", () -> moveDiff(dr, 1)));
        row.add(iconButton("genus", "Make this the genus (is a kind of)", () -> promoteToGenus(dr)));
        row.add(iconButton("✕", "Remove", () -> {
            diffRows.remove(dr);
            rebuildDiffPanel();
            updateCompoundSbvr();
        }));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffRows.add(dr);
        rebuildDiffPanel();
    }

    private JButton iconButton(String text, String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setMargin(new java.awt.Insets(1, 4, 1, 4));
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void moveDiff(DiffRow dr, int delta) {
        int i = diffRows.indexOf(dr);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= diffRows.size()) {
            return;
        }
        diffRows.set(i, diffRows.get(j));
        diffRows.set(j, dr);
        rebuildDiffPanel();
        updateCompoundSbvr();
    }

    /** Promote a differentia to the genus; the old genus (if any) becomes a differentia row. */
    private void promoteToGenus(DiffRow dr) {
        ConceptRef oldGenus = genus;
        genus = dr.concept;
        diffRows.remove(dr);
        rebuildDiffPanel();
        if (oldGenus != null) {
            addDifferentia(oldGenus);
        }
        refreshGenus();
        updateCompoundSbvr();
    }

    private void rebuildDiffPanel() {
        diffPanel.removeAll();
        for (DiffRow dr : diffRows) {
            diffPanel.add(dr.panel);
        }
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
        if (genus == null && diffRows.isEmpty()) {
            compoundSbvr.setText("Search for a concept, then \"Set as genus\" or \"Add as → relation\" "
                    + "(in any order).");
            return;
        }
        String text = previewSbvr();
        if (genus == null) {
            text += "\n\n(tip: set a genus with \"Set as genus\" or a row's \"genus\" button.)";
        }
        compoundSbvr.setText(text);
        compoundSbvr.setCaretPosition(0);
    }

    private static String camelCase(String s) {
        String[] w = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : w) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.length() == 0 ? "Concept" : sb.toString();
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
