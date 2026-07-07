// VerifyScopeAware.groovy
// =====================================================================================
// Live end-to-end proof of SCOPE-AWARE suggestions through the thin client -> service
// (UC-2.8). Creates two elements with the SAME name but different CONSTRUCT KIND and
// selects each in the containment tree (fires the plugin's selection handler ->
// ScopeContextBuilder -> CatalogServiceClient.suggest(..., scope) -> service):
//   * Class    "government"  -> STRUCTURE -> BFO object  -> cco:Government (ont00001335) leads
//   * Activity "government"  -> BEHAVIOR  -> BFO occurrent-> cco:Act of Government (ont00000142)
//                                                            ranks ABOVE the exact object
// Reads the SUGGEST journal line for each; asserts via=service(scope) AND the ordering flip.
// Requires: an open project + the plugin connected to the catalog service (SERVICE journal).
// Trace: design/use_cases.md UC-2.8
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String PKG_NAME = 'ScopeAwareIT'
final String GOV = 'http://www.commoncoreontologies.org/ont00001335' // Government (object)
final String ACT = 'http://www.commoncoreontologies.org/ont00000142' // Act of Government (occurrent)
final String GOV_CURIE = 'ont00001335'
final String ACT_CURIE = 'ont00000142'

final File LOG = new File(LOG_DIR, 'VerifyScopeAware.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
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

diag('=== VerifyScopeAware START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { fail('No open project - run OpenScratchProject first.'); diag('RESULT: FAIL'); return }

try {
    def sm = SessionManager.getInstance()
    def holder = [:]
    def error = null
    SwingUtilities.invokeAndWait {
        sm.createSession(project, 'ScopeAware create probes')
        try {
            def ef = project.getElementsFactory()
            def root = project.getPrimaryModel()
            def pkg = root.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
            if (pkg == null) { pkg = ef.createPackageInstance(); pkg.setName(PKG_NAME); pkg.setOwner(root) }
            long id = System.currentTimeMillis()
            def gClass = ef.createClassInstance();    gClass.setName('government'); gClass.setOwner(pkg)
            def gAct   = ef.createActivityInstance();  gAct.setName('government');   gAct.setOwner(pkg)
            holder.pkg = pkg; holder.gClass = gClass; holder.gAct = gAct
            sm.closeSession(project)
        } catch (Throwable t) {
            try { sm.cancelSession(project) } catch (Throwable ignored) {}
            error = t
        }
    }
    if (error != null) { throw error }
    diag('created Class government (' + holder.gClass.getHumanType() + ') and Activity government ('
            + holder.gAct.getHumanType() + ') in ' + PKG_NAME)

    // Clear any lingering search text (a prior test may have left the box dirty), so selection
    // is ELEMENT-DRIVEN (scope-aware) rather than a stale typed search. The plugin also clears
    // on selection now; this keeps the test deterministic in isolation.
    def findByName = { String nm ->
        def ref = new AtomicReference()
        def walk
        walk = { java.awt.Component c ->
            if (ref.get() != null) { return }
            if (nm == c.getName()) { ref.set(c); return }
            if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } }
        }
        SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (ref.get() == null) { walk(w) } } }
        return ref.get()
    }
    def searchField = findByName('semantic.conceptField')
    if (searchField != null) { SwingUtilities.invokeAndWait { searchField.setText('') } }

    def selectAndRead = { el, String label ->
        if (searchField != null) { SwingUtilities.invokeAndWait { searchField.setText('') } }
        long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
        SwingUtilities.invokeAndWait {
            def tree = app.getMainFrame().getBrowser().getContainmentTree()
            def path = tree.openNode(el)
            if (path != null) { tree.getTree().setSelectionPath(path) }
        }
        def line = waitForJournalLine(mark, ['| SUGGEST |', 'government'], 12000)
        diag(label + ' SUGGEST: ' + (line == null ? '<none>' : line.trim()))
        return line
    }
    def rankOf = { String line, String curie ->
        if (line == null) { return -1 }
        def m = (line =~ /top=(.*)$/)
        if (!m.find()) { return -1 }
        def items = m.group(1).split(',')
        for (int i = 0; i < items.length; i++) { if (items[i].contains(curie)) { return i } }
        return -1
    }

    // --- STRUCTURE (Class) ---
    def sLine = selectAndRead(holder.gClass, 'Class/STRUCTURE')
    // --- BEHAVIOR (Activity) ---
    def bLine = selectAndRead(holder.gAct, 'Activity/BEHAVIOR')

    if (sLine == null || bLine == null) { fail('missing SUGGEST journal line(s)') }
    else {
        boolean sVia = sLine.contains('via=service')
        boolean bVia = bLine.contains('via=service')
        diag('STRUCTURE via=service? ' + sVia + '  |  BEHAVIOR via=service? ' + bVia)
        if (!sVia || !bVia) { fail('suggestions did not route through the service (thin-client not connected)') }

        int sGov = rankOf(sLine, GOV_CURIE), sAct = rankOf(sLine, ACT_CURIE)
        int bGov = rankOf(bLine, GOV_CURIE), bAct = rankOf(bLine, ACT_CURIE)
        diag('STRUCTURE ranks: Government=' + sGov + ' ActOfGovernment=' + sAct)
        diag('BEHAVIOR  ranks: Government=' + bGov + ' ActOfGovernment=' + bAct)

        // STRUCTURE: the object Government must lead the process Act of Government.
        if (sGov < 0 || (sAct >= 0 && sGov > sAct)) {
            fail('STRUCTURE did not rank Government (object) above Act of Government')
        } else { diag('OK STRUCTURE: Government (object) leads') }
        // BEHAVIOR: the process Act of Government must rank above the exact object Government.
        if (bAct < 0 || (bGov >= 0 && bAct > bGov)) {
            fail('BEHAVIOR did not lift Act of Government (process) above Government')
        } else { diag('OK BEHAVIOR: Act of Government (process) lifted above the exact object') }
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in VerifyScopeAware', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== VerifyScopeAware DONE ===')
