// IT3_GuiSelection.groovy
// =====================================================================================
// Integration test 3: FULL GUI interaction. Programmatically selects fixture elements in
// the containment tree (real TreeSelectionListener path) and asserts the plugin sidebar
// reacted by reading the DiagnosticLog journal (SELECTION events). Then maps the
// unmapped GuiMappingProbe through the sidebar's concept TextField on the JavaFX thread
// (real user path: type IRI + Enter) and asserts the MAPPING event and model change.
// Requires IT1. Trace: PLG-REQ-03, PLG-REQ-04
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String PKG_NAME = 'SemanticAlignmentIT'
final String GUI_CONCEPT = 'sumo:Device'

final File LOG = new File(LOG_DIR, 'IT3_GuiSelection.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

final File JOURNAL = new File(new File(System.getProperty('user.home'), '.semantic_alignment_plugin'), 'semantic-plugin.log')
// Polls the journal for a line containing all tokens, appearing after byte offset `from`.
def waitForJournalLine = { long from, List<String> tokens, int timeoutMs ->
    long deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (JOURNAL.exists() && JOURNAL.length() > from) {
            def raf = new RandomAccessFile(JOURNAL, 'r')
            try {
                raf.seek(from)
                byte[] buf = new byte[(int) (JOURNAL.length() - from)]
                raf.readFully(buf)
                String tail = new String(buf, 'UTF-8')
                def line = tail.readLines().find { l -> tokens.every { l.contains(it) } }
                if (line != null) { return line }
            } finally { raf.close() }
        }
        Thread.sleep(200)
    }
    return null
}

diag('=== IT3 gui-selection START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { fail('No open project.'); diag('RESULT: FAIL'); return }

def pkg = project.getPrimaryModel().getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
if (pkg == null) { fail('Fixture package missing - run IT1 first.'); diag('RESULT: FAIL'); return }
def echoBase = pkg.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'EchoBase' }
def guiProbe = pkg.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'GuiMappingProbe' }
if (echoBase == null || guiProbe == null) { fail('Fixture elements missing - run IT1 first.'); diag('RESULT: FAIL'); return }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { fail('Plugin not loaded.'); diag('RESULT: FAIL'); return }
def pluginCL = plugin.getClass().getClassLoader()

// Selects an element in the containment tree on the EDT via the real JTree selection
// path, which fires the plugin's TreeSelectionListener exactly like a user click.
def selectInTree = { element ->
    def error = null
    SwingUtilities.invokeAndWait {
        try {
            def browser = app.getMainFrame().getBrowser()
            def tree = browser.getContainmentTree()
            if (tree == null) { throw new IllegalStateException('containment tree unavailable') }
            def path = tree.openNode(element)
            if (path != null) { tree.getTree().setSelectionPath(path) }
            else { throw new IllegalStateException('openNode returned no path for ' + element) }
        } catch (Throwable t) { error = t }
    }
    if (error != null) { throw error }
}

// Runs a closure on the JavaFX thread (toolkit lives in the plugin's classloader).
def onFx = { Closure work ->
    def platformCls = Class.forName('javafx.application.Platform', true, pluginCL)
    def latch = new CountDownLatch(1)
    def error = new AtomicReference()
    platformCls.getMethod('runLater', Runnable).invoke(null, {
        try { work.call() } catch (Throwable t) { error.set(t) } finally { latch.countDown() }
    } as Runnable)
    if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException('FX task timed out') }
    if (error.get() != null) { throw (Throwable) error.get() }
}

// Finds the sidebar's JFXPanel by walking the Swing tree under all windows.
def findSidebarFxPanel = {
    def result = null
    def walk
    walk = { java.awt.Component c ->
        if (result != null) { return }
        if (c.getClass().getName().endsWith('JFXPanel')) {
            def p = c.getParent()
            while (p != null) {
                if (p.getClass().getName().contains('SemanticBrowserPanel')) { result = c; return }
                p = p.getParent()
            }
        }
        if (c instanceof java.awt.Container) {
            c.getComponents().each { walk(it) }
        }
    }
    java.awt.Window.getWindows().each { w -> if (result == null) { walk(w) } }
    return result
}

try {
    // --- Phase A: selection follows the tree ---------------------------------------
    long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    selectInTree(echoBase)
    def selLine = waitForJournalLine(mark, ['| SELECTION |', 'Echo Base'], 8000)
    if (selLine == null) {
        fail('no SELECTION journal event for EchoBase within 8s (listener not firing?)')
    } else {
        diag('selection event: ' + selLine.trim())
        if (selLine.contains('sbvr=Instance: Echo Base is a Military Base.')) {
            diag('SBVR sentence correct for mapped element')
        } else {
            fail('SBVR sentence wrong or missing in: ' + selLine.trim())
        }
    }

    // --- Phase B: sidebar panel exists in the Swing tree ---------------------------
    def fxPanelHolder = new AtomicReference()
    SwingUtilities.invokeAndWait { fxPanelHolder.set(findSidebarFxPanel()) }
    def fxPanel = fxPanelHolder.get()
    if (fxPanel == null) {
        fail('sidebar JFXPanel not found in any window (panel not registered?)')
    } else {
        diag('sidebar JFXPanel found: ' + fxPanel.getClass().getName())
    }

    // --- Phase C: map GuiMappingProbe through the sidebar text field ----------------
    if (fxPanel != null) {
        mark = JOURNAL.exists() ? JOURNAL.length() : 0L
        selectInTree(guiProbe)
        def probeSel = waitForJournalLine(mark, ['| SELECTION |', 'Gui Mapping Probe'], 8000)
        if (probeSel == null) {
            fail('no SELECTION event for GuiMappingProbe')
        } else if (!probeSel.contains('concept=-')) {
            diag('note: GuiMappingProbe already mapped (rerun) - GUI mapping still exercised')
        }

        mark = JOURNAL.exists() ? JOURNAL.length() : 0L
        onFx {
            def scene = fxPanel.getScene()
            if (scene == null) { throw new IllegalStateException('JFXPanel has no scene yet') }
            def field = scene.getRoot().lookupAll('.text-field').find {
                it.respondsTo('getPromptText') && it.getPromptText()?.contains('Concept IRI')
            }
            if (field == null) { throw new IllegalStateException('concept TextField not found in scene') }
            field.setText(GUI_CONCEPT)
            def handler = field.getOnAction()
            if (handler == null) { throw new IllegalStateException('concept TextField has no onAction handler') }
            handler.handle(null) // same path as the user pressing Enter
        }
        def mapLine = waitForJournalLine(mark, ['| MAPPING |', 'Gui Mapping Probe', 'status=OK'], 8000)
        if (mapLine == null) {
            fail('no successful MAPPING journal event within 8s')
        } else {
            diag('mapping event: ' + mapLine.trim())
        }
        // Model-level confirmation
        def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
        if (stereo != null && StereotypesHelper.hasStereotype(guiProbe, stereo)) {
            diag('model confirms GuiMappingProbe is now aligned')
        } else {
            fail('model does not show the stereotype on GuiMappingProbe after GUI mapping')
        }
        // Sidebar refresh shows the new alignment
        def refreshed = waitForJournalLine(mark, ['| SELECTION |', 'Gui Mapping Probe', GUI_CONCEPT], 8000)
        if (refreshed == null) {
            fail('sidebar did not refresh with the new concept after mapping')
        } else {
            diag('sidebar refresh event: ' + refreshed.trim())
        }
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT3', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT3 DONE ===')
