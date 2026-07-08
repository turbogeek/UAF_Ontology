// ProbeComposeDialog.groovy — drives the redesigned Compose composer:
// open it, SEARCH "Mosquito" (online), add a result as a differentia, read the LIVE SBVR.
// The dialog is MODAL (holds the EDT), so once open we drive it ONLY via invokeLater and read
// state into a shared map (invokeAndWait from the EDT's nested pump would throw).
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final File LOG = new File(LOG_DIR, 'ProbeComposeDialog.log'); LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
boolean pass = true; def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== ProbeComposeDialog START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { fail('no project (run OpenScratchProject first)'); diag('RESULT: FAIL'); return }

// EDT-safe recursive find (call only while ON the EDT, e.g. inside invokeLater)
def findOnEdt
findOnEdt = { String nm, java.awt.Component c -> if (nm == c.getName()) return c; if (c instanceof java.awt.Container) { for (ch in c.getComponents()) { def r = findOnEdt(nm, ch); if (r) return r } }; return null }
def findWin = { String nm -> for (w in java.awt.Window.getWindows()) { def r = findOnEdt(nm, w); if (r) return r }; return null }

try {
    // create + select an element (needs the tree; do this BEFORE the modal opens, invokeAndWait ok)
    def holder = [:]; def err = null
    SwingUtilities.invokeAndWait {
        def sm = SessionManager.getInstance(); sm.createSession(project, 'compose probe')
        try {
            def ef = project.getElementsFactory(); def root = project.getPrimaryModel()
            def pkg = root.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'ComposeProbeIT' }
            if (pkg == null) { pkg = ef.createPackageInstance(); pkg.setName('ComposeProbeIT'); pkg.setOwner(root) }
            def el = ef.createClassInstance(); el.setName('MosquitoKillingDrone'); el.setOwner(pkg); holder.el = el
            sm.closeSession(project)
        } catch (Throwable t) { try { sm.cancelSession(project) } catch (Throwable ig) {}; err = t }
    }
    if (err != null) throw err
    def treeRef = new AtomicReference()
    for (int i = 0; i < 60 && treeRef.get() == null; i++) { SwingUtilities.invokeAndWait { try { treeRef.set(app.getMainFrame().getBrowser().getContainmentTree()) } catch (Throwable t) {} }; if (treeRef.get() == null) Thread.sleep(250) }
    if (treeRef.get() == null) { fail('tree not ready'); diag('RESULT: FAIL'); return }
    SwingUtilities.invokeAndWait { def p = treeRef.get().openNode(holder.el); if (p != null) treeRef.get().getTree().setSelectionPath(p) }
    Thread.sleep(1500)

    def R = new ConcurrentHashMap()
    def doSearch = { String q -> SwingUtilities.invokeLater {   // leave 'online' at its default
        def sf = findWin('semantic.compose.searchField'); def sb = findWin('semantic.compose.searchButton')
        if (sf) sf.setText(q); if (sb) sb.doClick()
    } }
    // 1. open the composer (modal) + check defaults / new buttons
    SwingUtilities.invokeLater { def b = findWin('semantic.composeButton'); R.opened = (b != null); if (b) b.doClick() }
    Thread.sleep(2500)
    SwingUtilities.invokeLater {
        def oc = findWin('semantic.compose.onlineCheck'); R.onlineDefault = (oc ? oc.isSelected() : null)
        R.newConcept = (findWin('semantic.compose.newConceptButton') != null)
        R.newRelation = (findWin('semantic.compose.newRelationButton') != null)
    }
    Thread.sleep(500)
    // 2. search a GENUS ("aircraft") and add it as the genus
    doSearch('aircraft', false); Thread.sleep(4000)
    SwingUtilities.invokeLater {
        def rl = findWin('semantic.compose.resultsList'); R.dialogFound = (rl != null)
        R.genusResults = rl ? rl.getModel().getSize() : -1
        if (rl && rl.getModel().getSize() > 0) { rl.setSelectedIndex(0); R.genus = rl.getSelectedValue()?.toString() }
        def g = findWin('semantic.compose.addGenusButton'); if (g) g.doClick()
    }
    Thread.sleep(1500)
    // 3. search the QUALIFIER ("Mosquito", online) and add it as a differentia
    doSearch('Mosquito', true); Thread.sleep(6000)
    SwingUtilities.invokeLater {
        def rl = findWin('semantic.compose.resultsList')
        R.results = rl ? rl.getModel().getSize() : -1
        if (rl && rl.getModel().getSize() > 0) { rl.setSelectedIndex(0); R.first = rl.getSelectedValue()?.toString() }
        def meaning = findWin('semantic.compose.conceptSbvr'); R.meaning = meaning ? meaning.getText() : '?'
        def add = findWin('semantic.compose.addDiffButton'); if (add) add.doClick()
    }
    Thread.sleep(1500)
    // 4. set the relation to "kills", read final SBVR + dispose
    SwingUtilities.invokeLater {
        def rel = findWin('semantic.compose.relation.0'); if (rel) { rel.setSelectedItem('kills') }
    }
    Thread.sleep(800)
    SwingUtilities.invokeLater {
        def sv = findWin('semantic.compose.sbvrPreview'); R.sbvr = sv ? sv.getText() : '?'
        def dlg = findWin('semantic.composeDialog'); if (dlg) { dlg.setVisible(false); dlg.dispose() }
    }
    Thread.sleep(1200)

    diag('opened=' + R.opened + ' dialogFound=' + R.dialogFound)
    diag('genus search "aircraft" -> results=' + R.genusResults + '  genus="' + R.genus + '"')
    diag('qualifier search "Mosquito" online -> results=' + R.results + '  first="' + R.first + '"')
    diag('selected concept meaning: ' + (R.meaning ?: '').toString().replace('\n',' | '))
    diag('COMPOUND SBVR: ' + R.sbvr)
    if (!(R.opened)) fail('compose button not found')
    if (!((R.results ?: 0) > 0)) fail('online search returned no results')
    String s = (R.sbvr ?: '').toString().toLowerCase()
    if (!(s.contains('aircraft') && s.contains('mosquito'))) fail('compound SBVR missing genus+qualifier: ' + R.sbvr)
} catch (Throwable t) {
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag('UNCAUGHT\n' + sw.toString()); pass = false
}
diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== ProbeComposeDialog DONE ===')
