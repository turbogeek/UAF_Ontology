package com.nomagic.magicdraw.plugins.semantic.ui;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import com.nomagic.magicdraw.plugins.semantic.align.CompoundConcept;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Compose Concept" dialog: builds a COMPOUND concept for an element as a genus ("is a kind of")
 * plus differentia rows, each a curated relation phrase applied to a qualifier concept. A LIVE
 * SBVR preview updates as the modeler picks relations, so they can see it read correctly before
 * applying ("A Mosquito Suppression Drone is a Drone that suppresses a Mosquito ..."). On Apply it
 * hands back the encoded {@code mappedConceptURI} values (genus first, then {@code relation | IRI}).
 *
 * <p>Pure Swing (Cameo's runtime has no JavaFX). All controls carry stable names
 * ({@code semantic.compose.*}) for GUI-test lookup. Trace: design/compound_concepts.md</p>
 */
public final class ComposeConceptDialog extends JDialog {

    /** A candidate concept: its IRI plus a human label. */
    public record ConceptRef(String iri, String label) {
    }

    private final SBVREngine sbvr = new SBVREngine();
    private final JTextField nameField = new JTextField(24);
    private final String genusIri;
    private final String genusLabel;
    private final List<ConceptRef> qualifiers;
    private final List<JComboBox<String>> relationBoxes = new ArrayList<>();
    private final JTextArea sbvrPreview = new JTextArea(3, 40);

    public ComposeConceptDialog(Frame owner, String elementName, String genusIri, String genusLabel,
                                List<ConceptRef> qualifiers, List<String> relationPhrases,
                                Consumer<List<String>> onApply) {
        super(owner, "Compose Concept", true);
        this.genusIri = genusIri;
        this.genusLabel = genusLabel == null || genusLabel.isBlank()
                ? sbvr.getLocalName(genusIri) : genusLabel;
        this.qualifiers = qualifiers == null ? List.of() : qualifiers;
        setName("semantic.composeDialog");

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- header: the new concept name + its genus ---
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        header.add(new JLabel("This concept:"));
        nameField.setName("semantic.compose.nameField");
        nameField.setText(elementName == null ? "" : elementName);
        header.add(nameField);
        header.add(new JLabel("  is a kind of  "));
        JLabel genus = new JLabel(this.genusLabel);
        genus.setName("semantic.compose.genusLabel");
        header.add(genus);
        content.add(header, BorderLayout.NORTH);

        // --- differentia rows: one per qualifier concept ---
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < this.qualifiers.size(); i++) {
            ConceptRef q = this.qualifiers.get(i);
            gc.gridy = i;
            gc.gridx = 0;
            rows.add(new JLabel("that"), gc);
            JComboBox<String> rel = new JComboBox<>(relationPhrases.toArray(new String[0]));
            rel.setEditable(true);
            rel.setName("semantic.compose.relation." + i);
            rel.addActionListener(e -> updatePreview());
            relationBoxes.add(rel);
            gc.gridx = 1;
            rows.add(rel, gc);
            gc.gridx = 2;
            JLabel ql = new JLabel(q.label() == null ? sbvr.getLocalName(q.iri()) : q.label());
            ql.setName("semantic.compose.qualifier." + i);
            rows.add(ql, gc);
        }
        JScrollPane rowsScroll = new JScrollPane(rows);
        rowsScroll.setPreferredSize(new Dimension(520, 140));
        content.add(rowsScroll, BorderLayout.CENTER);

        // --- live SBVR preview + buttons ---
        JPanel south = new JPanel(new BorderLayout(6, 6));
        sbvrPreview.setName("semantic.compose.sbvrPreview");
        sbvrPreview.setEditable(false);
        sbvrPreview.setLineWrap(true);
        sbvrPreview.setWrapStyleWord(true);
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("SBVR (reads as)"));
        previewPanel.add(sbvrPreview, BorderLayout.CENTER);
        south.add(previewPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton apply = new JButton("Apply Compound Concept");
        apply.setName("semantic.compose.applyButton");
        apply.addActionListener(e -> {
            if (onApply != null) {
                onApply.accept(encodedClauses());
            }
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.setName("semantic.compose.cancelButton");
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        buttons.add(apply);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        updatePreview();
        pack();
        if (owner != null) {
            setLocationRelativeTo(owner);
        }
    }

    /** Current relation selections -> the CompoundConcept, for preview and apply. */
    private CompoundConcept currentCompound() {
        List<CompoundConcept.Clause> diff = new ArrayList<>();
        for (int i = 0; i < qualifiers.size(); i++) {
            String rel = String.valueOf(relationBoxes.get(i).getEditor().getItem()).trim();
            diff.add(new CompoundConcept.Clause(rel, qualifiers.get(i).iri()));
        }
        String label = nameField.getText() == null || nameField.getText().isBlank()
                ? "Concept" : nameField.getText().trim();
        return new CompoundConcept(label, genusIri, diff);
    }

    /** Encoded mappedConceptURI values (genus first, then relation|IRI clauses). */
    public List<String> encodedClauses() {
        return currentCompound().toStoredList();
    }

    /** The SBVR sentence for the current selections (exposed for tests). */
    public String previewSbvr() {
        return currentCompound().toSbvr(sbvr);
    }

    private void updatePreview() {
        sbvrPreview.setText(previewSbvr());
    }

    /** Convenience: build + show on the EDT. */
    public static void open(Frame owner, String elementName, String genusIri, String genusLabel,
                            List<ConceptRef> qualifiers, List<String> relationPhrases,
                            Consumer<List<String>> onApply) {
        SwingUtilities.invokeLater(() ->
                new ComposeConceptDialog(owner, elementName, genusIri, genusLabel,
                        qualifiers, relationPhrases, onApply).setVisible(true));
    }
}
