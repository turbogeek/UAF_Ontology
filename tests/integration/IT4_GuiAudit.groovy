// IT4_GuiAudit.groovy
// =====================================================================================
// Integration test 4: FULL GUI audit. Fires the sidebar's "Run Audit" JavaFX button
// (real user path), then asserts:
//   - an AUDIT journal event with consistent=true and shaclViolations=0
//   - last-audit-export.ttl exists, parses as Turtle (Jena via plugin classloader),
//     and contains the fixture's type/hasPart/connectedTo/label triples (PLG-REQ-01/02)
//   - the status badge reads "STATUS: CONSISTENT"
// Requires IT1 (fixture) and the plugin sidebar to be open. Trace: PLG-REQ-01/02/05/06
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'

final File LOG = new File(LOG_DIR, 'IT4_GuiAudit.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

final File DIAG_DIR = new File(System.getProperty('user.home'), '.semantic_alignment_plugin')
final File JOURNAL = new File(DIAG_DIR, 'semantic-plugin.log')
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
        Thread.sleep(250)
    }
    return null
}

diag('=== IT4 gui-audit START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
if (app.getProject() == null) { fail('No open project.'); diag('RESULT: FAIL'); return }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { fail('Plugin not loaded.'); diag('RESULT: FAIL'); return }
def pluginCL = plugin.getClass().getClassLoader()

def onFx = { Closure work ->
    def platformCls = Class.forName('javafx.application.Platform', true, pluginCL)
    def latch = new CountDownLatch(1)
    def error = new AtomicReference()
    def out = new AtomicReference()
    platformCls.getMethod('runLater', Runnable).invoke(null, {
        try { out.set(work.call()) } catch (Throwable t) { error.set(t) } finally { latch.countDown() }
    } as Runnable)
    if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException('FX task timed out') }
    if (error.get() != null) { throw (Throwable) error.get() }
    return out.get()
}

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
        if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } }
    }
    java.awt.Window.getWindows().each { w -> if (result == null) { walk(w) } }
    return result
}

try {
    def fxPanelHolder = new AtomicReference()
    SwingUtilities.invokeAndWait { fxPanelHolder.set(findSidebarFxPanel()) }
    def fxPanel = fxPanelHolder.get()
    if (fxPanel == null) { fail('sidebar JFXPanel not found.'); diag('RESULT: FAIL'); return }

    long mark = JOURNAL.exists() ? JOURNAL.length() : 0L

    // Fire the real GUI button on the FX thread - identical to a user click.
    onFx {
        def scene = fxPanel.getScene()
        def button = scene.getRoot().lookupAll('.button').find {
            it.respondsTo('getText') && it.getText() == 'Run Audit'
        }
        if (button == null) { throw new IllegalStateException('"Run Audit" button not found') }
        button.fire()
        return null
    }
    diag('Run Audit fired via JavaFX button')

    def auditLine = waitForJournalLine(mark, ['| AUDIT |'], 30000)
    if (auditLine == null) {
        fail('no AUDIT journal event within 30s')
    } else {
        diag('audit event: ' + auditLine.trim())
        if (!auditLine.contains('consistent=true')) { fail('audit reports inconsistency: ' + auditLine.trim()) }
        if (!auditLine.contains('shaclViolations=0')) { fail('audit reports SHACL violations: ' + auditLine.trim()) }
    }

    // Exported graph assertions (what the audit actually validated).
    def ttlFile = new File(DIAG_DIR, 'last-audit-export.ttl')
    if (!ttlFile.exists()) {
        fail('last-audit-export.ttl missing: ' + ttlFile)
    } else {
        String ttl = ttlFile.getText('UTF-8')
        diag('export size: ' + ttl.length() + ' chars')
        [['type assertion', 'MilitaryBase'],
         ['containment', 'hasPart'],
         ['association', 'connectedTo'],
         ['label', 'label']].each { what, token ->
            if (ttl.contains(token)) { diag('export contains ' + what + ' (' + token + ')') }
            else { fail('export missing ' + what + ' (' + token + ')') }
        }
        // Prove the Turtle is well-formed by parsing it with the plugin's own Jena.
        try {
            def mfCls = Class.forName('org.apache.jena.rdf.model.ModelFactory', true, pluginCL)
            def model = mfCls.getMethod('createDefaultModel').invoke(null)
            new FileInputStream(ttlFile).withCloseable { ins ->
                model.read(ins, null, 'TURTLE')
            }
            long size = model.size()
            if (size > 0) { diag('Jena parsed export OK: ' + size + ' triples') }
            else { fail('Jena parsed export but it contains zero triples') }
        } catch (Throwable t) {
            fail('export is not valid Turtle: ' + t)
        }
    }

    // Badge state (poll briefly - the FX update is queued after the journal write).
    String badgeText = null
    for (int i = 0; i < 20 && badgeText != 'STATUS: CONSISTENT'; i++) {
        badgeText = onFx {
            def scene = fxPanel.getScene()
            def badge = scene.getRoot().lookupAll('.label').find {
                it.respondsTo('getText') && it.getText()?.startsWith('STATUS:')
            }
            return badge == null ? null : badge.getText()
        }
        if (badgeText != 'STATUS: CONSISTENT') { Thread.sleep(250) }
    }
    if (badgeText == 'STATUS: CONSISTENT') { diag('badge OK: ' + badgeText) }
    else { fail('badge text after audit: ' + badgeText) }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT4', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT4 DONE ===')
