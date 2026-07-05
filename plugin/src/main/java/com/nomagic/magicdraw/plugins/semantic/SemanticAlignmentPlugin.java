package com.nomagic.magicdraw.plugins.semantic;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.Browser;
import com.nomagic.magicdraw.ui.browser.WindowComponent;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;
import com.nomagic.ui.ExtendedPanel;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Main lifecycle entry point for the UAF/SysML Semantic Integration Plugin.
 * Registers the custom sidebar tab and sets up thread safe Swing/JavaFX GUI bridging.
 * Trace: PLG-REQ-01, PLG-REQ-02, PLG-REQ-04
 */
public class SemanticAlignmentPlugin extends Plugin {

    private static final Logger log = Logger.getLogger(SemanticAlignmentPlugin.class);
    private static final String COMPONENT_ID = "SEMANTIC_ALIGNMENT_SIDEBAR";
    private static final String COMPONENT_NAME = "Semantic Alignment";

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
        log.info("Initializing UAF / SysML Semantic Alignment Plugin...");
        
        // Register the panel to the MagicDraw/Cameo browser tab
        Browser.addBrowserInitializer(new Browser.BrowserInitializer() {
            @Override
            public void init(Browser browser, Project project) {
                browser.addPanel(new SemanticBrowserPanel());
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
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    /**
     * Swing browser panel container holding the JavaFX panel.
     */
    private static final class SemanticBrowserPanel extends ExtendedPanel implements WindowComponent {
        private JFXPanel jfxPanel;

        public SemanticBrowserPanel() {
            super(new BorderLayout());
            
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
         * Renders the JavaFX UI layout inside the JFXPanel.
         */
        private void initFX(JFXPanel panel) {
            VBox root = new VBox(10);
            root.setPadding(new Insets(12));
            root.setStyle("-fx-background-color: #0f172a;"); // Dark slate background matching graphify theme

            // Top section: Selected element card
            Label selectionLabel = new Label("Selected Element: EchoBase");
            selectionLabel.setStyle("-fx-text-fill: #f8fafc; -fx-font-weight: bold; -fx-font-size: 13px;");
            
            Label typeLabel = new Label("Stereotype: UAF::ActualOrganization");
            typeLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");

            VBox metaCard = new VBox(4, selectionLabel, typeLabel);
            metaCard.setPadding(new Insets(10));
            metaCard.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 6px; -fx-border-color: #334155; -fx-border-radius: 6px;");
            root.getChildren().add(metaCard);

            // Middle section: SBVR View
            Label sbvrHeader = new Label("SBVR Structured English");
            sbvrHeader.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-font-weight: bold;");
            
            TextArea sbvrArea = new TextArea("Instance: EchoBase is a MilitaryBase.\nInstance: EchoBase contains OperationalCommand.");
            sbvrArea.setEditable(false);
            sbvrArea.setWrapText(true);
            sbvrArea.setPrefHeight(90);
            sbvrArea.setStyle("-fx-control-inner-background: #1e293b; -fx-text-fill: #38bdf8; -fx-font-family: \"Courier New\", monospace; -fx-font-size: 12px;");
            root.getChildren().addAll(sbvrHeader, sbvrArea);

            // Autocomplete Search
            Label searchHeader = new Label("Align with Ontology Concept");
            searchHeader.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-font-weight: bold;");
            
            TextField searchField = new TextField();
            searchField.setPromptText("Type to search (e.g. MilitaryUnit, Organization)...");
            searchField.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #f8fafc; -fx-prompt-text-fill: #64748b; -fx-border-color: #334155; -fx-border-radius: 4px;");
            root.getChildren().addAll(searchHeader, searchField);

            // Bottom section: Validation dashboard
            Label statusBadge = new Label("STATUS: CONSISTENT");
            statusBadge.setStyle("-fx-background-color: #059669; -fx-text-fill: #ffffff; -fx-padding: 4px 8px; -fx-background-radius: 4px; -fx-font-weight: bold; -fx-font-size: 11px;");
            root.getChildren().add(statusBadge);

            Scene scene = new Scene(root, 300, 500);
            panel.setScene(scene);
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
