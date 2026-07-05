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
import java.awt.*;

/**
 * Main lifecycle entry point for the UAF/SysML Semantic Integration Plugin.
 * Registers the custom sidebar tab and sets up thread safe GUI bridging.
 * Trace: PLG-REQ-01, PLG-REQ-02
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
        // Supported on all modeling environments (UML, SysMLv1, SysMLv2)
        return true;
    }

    /**
     * Panel component representing the sidebar panel inside Cameo.
     */
    private static final class SemanticBrowserPanel extends ExtendedPanel implements WindowComponent {

        public SemanticBrowserPanel() {
            super(new BorderLayout());
            // Swing Label inside the Panel (JFXPanel will be initialized here in production)
            JLabel label = new JLabel("Semantic Alignment Dashboard Active");
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setForeground(new Color(148, 163, 184)); // Muted slate color matching mockup
            add(label, BorderLayout.CENTER);
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
        public Component getWindowComponent() {
            return panel;
        }

        @Override
        public Component getDefaultFocusComponent() {
            return panel;
        }
    }
}
