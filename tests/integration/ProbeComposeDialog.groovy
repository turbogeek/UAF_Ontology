// ProbeComposeDialog.groovy
// Reproduces the owner's report: clicking "Compose Concept" WITHOUT Ctrl-selecting rows.
// After the fix it must still open the dialog (falling back to displayed suggestions).
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final File LOG = new File(LOG_DIR, 'ProbeComposeDialog.log'); LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
boolean pass = true; def fail = { String m -> pass = false; diag('FAIL: ' + m) }
final File JOURNAL = new File(new File(System.getProperty('user.home'), '.semantic_alignment_plugin'), 'semantic-plugin.log')

diag('=== ProbeComposeDialog START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) {
    SwingUtilities.invokeLater { try { app.getProjectsManager().createProject() } catch (Throwable ig) {} }
    for (int i = 0; i < 75 && project == null; i++) { Thread.sleep(200); project = app.getProject() }
    Thread.sleep(2500)
}
if (project == null) { fail('no project'); diag('RESULT: FAIL'); return }

def findByName = { String nm ->
    def ref = new AtomicReference(); def walk
    walk = { java.awt.Component c -> if (ref.get() != null) return; if (nm == c.getName()) { ref.set(c); return }; if (c instanceof java.awt.Container) c.getComponents().each { walk(it) } }
    SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (ref.get() == null) walk(w) } }
    return ref.get()
}
def waitLine = { long from, List toks, int ms ->
    long dl = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < dl) {
        if (JOURNAL.exists() && JOURNAL.length() > from) {
            def raf = new RandomAccessFile(JOURNAL, 'r'); try { raf.seek(from); byte[] b = new byte[(int)(JOURNAL.length()-from)]; raf.readFully(b)
                def ln = new String(b,'UTF-8').readLines().find { l -> toks.every { l.contains(it) } }; if (ln) return ln } finally { raf.close() }
        }
        Thread.sleep(200)
    }
    return null
}

try {
    // create + select a plain "engine" element (no base concept, no row selection) -> suggestions show
    def holder = [:]; def err = null
    SwingUtilities.invokeAndWait {
        def sm = SessionManager.getInstance(); sm.createSession(project, 'compose probe')
        try {
            def ef = project.getElementsFactory(); def root = project.getPrimaryModel()
            def pkg = root.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'ComposeProbeIT' }
            if (pkg == null) { pkg = ef.createPackageInstance(); pkg.setName('ComposeProbeIT'); pkg.setOwner(root) }
            def el = ef.createClassInstance(); el.setName('engine'); el.setOwner(pkg); holder.el = el
            sm.closeSession(project)
        } catch (Throwable t) { try { sm.cancelSession(project) } catch (Throwable ig) {}; err = t }
    }
    if (err != null) throw err
    // Wait for the containment tree to be ready (a freshly-created project's browser lags).
    def treeRef = new AtomicReference()
    for (int i = 0; i < 60 && treeRef.get() == null; i++) {
        SwingUtilities.invokeAndWait { try { treeRef.set(app.getMainFrame().getBrowser().getContainmentTree()) } catch (Throwable t) {} }
        if (treeRef.get() == null) Thread.sleep(250)
    }
    def tree = treeRef.get()
    if (tree == null) { fail('containment tree not ready'); diag('RESULT: FAIL'); return }
    long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    SwingUtilities.invokeAndWait {
        def path = tree.openNode(holder.el)
        if (path != null) tree.getTree().setSelectionPath(path)
    }
    def sug = waitLine(mark, ['| SUGGEST |', 'engine'], 12000)
    diag('suggestions populated: ' + (sug != null))

    def list = findByName('semantic.suggestionList')
    int shown = 0; if (list != null) SwingUtilities.invokeAndWait { shown = list.getModel().getSize() }
    diag('displayed suggestions: ' + shown + ' (NOT selecting any row - reproducing the report)')

    def btn = findByName('semantic.composeButton')
    if (btn == null) { fail('compose button not found'); diag('RESULT: FAIL'); return }
    long cmark = JOURNAL.exists() ? JOURNAL.length() : 0L
    // click via invokeLater so the MODAL dialog does not block this harness thread
    SwingUtilities.invokeLater { btn.doClick() }
    Thread.sleep(2500)

    def dlg = findByName('semantic.composeDialog')
    def composeLine = waitLine(cmark, ['COMPOSE'], 3000)
    diag('COMPOSE journal: ' + (composeLine == null ? '<none>' : composeLine.trim()))
    if (dlg == null) { fail('compose dialog did NOT open') }
    else {
        boolean vis = false; String sbvr = ''
        SwingUtilities.invokeAndWait { vis = dlg.isVisible(); def pv = findByName('semantic.compose.sbvrPreview'); if (pv != null) sbvr = pv.getText() }
        diag('dialog OPEN, visible=' + vis + '  preview="' + sbvr + '"')
        if (!vis) fail('dialog created but not visible')
        SwingUtilities.invokeLater { dlg.dispose() } // clean up (modal) so the harness can finish
        Thread.sleep(500)
    }
} catch (Throwable t) {
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag('UNCAUGHT\n' + sw.toString()); pass = false
}
diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== ProbeComposeDialog DONE ===')
