package com.nomagic.magicdraw.plugins.semantic;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts user interactions (clicks, keystrokes) landing on plugin components so the
 * click-reduction budgets in the v3 plan are measured, not aspirational. Scenario tests
 * read and reset these counters through the REST /metrics endpoint and assert budgets
 * like "single alignment <= 2 clicks". Scoping: an event counts only when the source
 * component or an ancestor carries a name starting with "semantic.".
 * Trace: v3 plan section 5
 */
public final class UxMetrics {

    private static final AtomicLong CLICKS = new AtomicLong();
    private static final AtomicLong KEYSTROKES = new AtomicLong();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    // Private constructor to prevent instantiation of utility class
    private UxMetrics() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(UxMetrics::onEvent,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        DiagnosticLog.event("METRICS", "UX metrics listener installed");
    }

    private static void onEvent(AWTEvent event) {
        if (!(event.getSource() instanceof Component component) || !isPluginComponent(component)) {
            return;
        }
        if (event.getID() == MouseEvent.MOUSE_CLICKED) {
            CLICKS.incrementAndGet();
        } else if (event.getID() == KeyEvent.KEY_TYPED) {
            KEYSTROKES.incrementAndGet();
        }
    }

    private static boolean isPluginComponent(Component component) {
        for (Component c = component; c != null; c = c.getParent()) {
            String name = c.getName();
            if (name != null && name.startsWith("semantic.")) {
                return true;
            }
        }
        return false;
    }

    public static long clicks() {
        return CLICKS.get();
    }

    public static long keystrokes() {
        return KEYSTROKES.get();
    }

    public static void reset() {
        CLICKS.set(0);
        KEYSTROKES.set(0);
    }

    public static String toJson() {
        return "{\"clicks\":" + CLICKS.get() + ",\"keystrokes\":" + KEYSTROKES.get() + "}";
    }
}
