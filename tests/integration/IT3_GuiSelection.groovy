// IT3_GuiSelection.groovy
// =====================================================================================
// Integration test 3: FULL GUI interaction. Programmatically selects fixture elements in
// the containment tree (real TreeSelectionListener path) and asserts the plugin sidebar
// reacted - both via the DiagnosticLog journal (SELECTION events) and by reading the
// Swing sidebar's labels directly. Then maps the unmapped GuiMappingProbe through the
// sidebar's concept text field (real user path: type IRI + Enter).
// Requires IT1. Trace: PLG-REQ-03, PLG-REQ-04
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
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
def waitForJournalLine = { long from, List<String> tokens, int timeoutMs ->
    long deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (JOURNAL.exists() && JOURNAL.length() > from) {
            def raf = new RandomAccessFile(JOURNAL, 'r')
            try {
                raf.seek(from)
                byte[] buf = new byte[(int) (JOURNAL.length() - from)]
                raf.readFully(buf)
                def line = new String(buf, 'UTF-8').readLines().find { l -> tokens.every { l.contains(it) } }
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

// Finds a component by its stable name anywhere under any window (the sidebar sets
// semantic.* names exactly for this purpose).
def findByName = { String name ->
    def result = new AtomicReference()
    def walk
    walk = { java.awt.Component c ->
        if (result.get() != null) { return }
        if (name == c.getName()) { result.set(c); return }
        if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } }
    }
    SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (result.get() == null) { walk(w) } } }
    return result.get()
}

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

def readOnEdt = { Closure work ->
    def result = new AtomicReference()
    SwingUtilities.invokeAndWait { result.set(work.call()) }
    return result.get()
}

try {
    // --- Phase A: selection follows the tree ---------------------------------------
    long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    selectInTree(echoBase)
    def selLine = waitForJournalLine(mark, ['| SELECTION |', 'EchoBase'], 8000)
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

    // --- Phase B: sidebar Swing components reflect the selection --------------------
    def selectionLabel = findByName('semantic.selectionLabel')
    def sbvrArea = findByName('semantic.sbvrArea')
    if (selectionLabel == null || sbvrArea == null) {
        fail('sidebar components not found by name (panel not registered?)')
    } else {
        // The sidebar updates via invokeLater; give the queue a moment.
        String labelText = null
        for (int i = 0; i < 20; i++) {
            labelText = readOnEdt { selectionLabel.getText() }
            if (labelText?.contains('EchoBase')) { break }
            Thread.sleep(200)
        }
        if (labelText?.contains('EchoBase')) {
            diag('sidebar label OK: ' + labelText)
        } else {
            fail('sidebar selection label did not update: ' + labelText)
        }
        String sbvrText = readOnEdt { sbvrArea.getText() }
        if (sbvrText == 'Instance: Echo Base is a Military Base.') {
            diag('sidebar SBVR area OK: ' + sbvrText)
        } else {
            fail('sidebar SBVR area text: ' + sbvrText)
        }
    }

    // --- Phase C: map GuiMappingProbe through the sidebar text field ----------------
    mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    selectInTree(guiProbe)
    def probeSel = waitForJournalLine(mark, ['| SELECTION |', 'GuiMappingProbe'], 8000)
    if (probeSel == null) {
        fail('no SELECTION event for GuiMappingProbe')
    } else if (!probeSel.contains('concept=-')) {
        diag('note: GuiMappingProbe already mapped (rerun) - GUI mapping still exercised')
    }

    def conceptField = findByName('semantic.conceptField')
    if (conceptField == null) {
        fail('concept text field not found by name')
    } else {
        mark = JOURNAL.exists() ? JOURNAL.length() : 0L
        SwingUtilities.invokeAndWait {
            conceptField.setText(GUI_CONCEPT)
            conceptField.postActionEvent() // same path as the user pressing Enter
        }
        def mapLine = waitForJournalLine(mark, ['| MAPPING |', 'GuiMappingProbe', 'status=OK'], 8000)
        if (mapLine == null) {
            fail('no successful MAPPING journal event within 8s')
        } else {
            diag('mapping event: ' + mapLine.trim())
        }
        def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
        if (stereo != null && StereotypesHelper.hasStereotype(guiProbe, stereo)) {
            diag('model confirms GuiMappingProbe is now aligned')
        } else {
            fail('model does not show the stereotype on GuiMappingProbe after GUI mapping')
        }
        def refreshed = waitForJournalLine(mark, ['| SELECTION |', 'GuiMappingProbe', GUI_CONCEPT], 8000)
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
