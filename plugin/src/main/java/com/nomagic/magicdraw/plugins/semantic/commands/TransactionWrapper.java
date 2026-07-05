package com.nomagic.magicdraw.plugins.semantic.commands;

import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.core.Project;
import org.apache.log4j.Logger;

/**
 * Utility wrapper that enforces safe MagicDraw/Cameo model write transactions.
 * Transactions are added directly to the project command history, enabling Undo/Redo operations.
 * Trace: PLG-REQ-06
 */
public final class TransactionWrapper {

    private static final Logger log = Logger.getLogger(TransactionWrapper.class);

    // Private constructor to prevent instantiation of utility class
    private TransactionWrapper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Executes a write operation within a managed transaction block.
     * Automatically cancels/rolls back changes if an exception occurs.
     *
     * @param project     The active MagicDraw project instance.
     * @param sessionName The name of the transaction session (visible in the undo stack).
     * @param operation   The executable task that modifies model elements.
     */
    public static void executeWrite(Project project, String sessionName, Runnable operation) {
        if (project == null) {
            throw new IllegalArgumentException("Project parameter cannot be null.");
        }
        if (sessionName == null || sessionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Session name cannot be empty.");
        }
        if (operation == null) {
            throw new IllegalArgumentException("Operation parameter cannot be null.");
        }

        SessionManager manager = SessionManager.getInstance();
        manager.createSession(project, sessionName);
        try {
            // Run the custom modification logic
            operation.run();
            manager.closeSession(project);
            log.debug("Semantic transaction successfully closed: " + sessionName);
        } catch (Throwable t) {
            // Cancel transaction to roll back all model modifications
            manager.cancelSession(project);
            log.error("Error in model transaction. Aborting and rolling back session: " + sessionName, t);
            throw new RuntimeException("Model modification transaction failed", t);
        }
    }
}
