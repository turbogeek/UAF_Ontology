// IT5_AuditViolations.groovy
// =====================================================================================
// Integration test 5: negative-path audits through the real GUI button.
//   Phase A - TBox inconsistency: drops a disjointness TBox into the plugin's tbox/
//             folder (read per-audit, no restart) making EchoBase provably inconsistent
//             (hasPart domain=Vehicle, Vehicle disjointWith MilitaryBase). Expects
//             consistent=false and a red INCONSISTENT badge.
//   Phase B - SHACL violations: points -Dsemantic.plugin.shapes at a shapes file that
//             requires a property no exported individual has. Expects violations>0.
//   Phase C - cleanup and green re-audit: removes the TBox + property override, expects
//             consistent=true / 0 violations again (proves audits are stateless).
// Requires IT1 fixture. Trace: PLG-REQ-05, PLG-REQ-06
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'

final File LOG = new File(LOG_DIR, 'IT5_AuditViolations.log')
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

diag('=== IT5 audit-violations START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
if (app.getProject() == null) { fail('No open project.'); diag('RESULT: FAIL'); return }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { fail('Plugin not loaded.'); diag('RESULT: FAIL'); return }
def pluginDir = plugin.getDescriptor()?.getPluginDirectory()
if (pluginDir == null) { fail('Plugin directory unresolved.'); diag('RESULT: FAIL'); return }

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
def readOnEdt = { Closure work ->
    def result = new AtomicReference()
    SwingUtilities.invokeAndWait { result.set(work.call()) }
    return result.get()
}

def auditButton = findByName('semantic.auditButton')
def statusBadge = findByName('semantic.statusBadge')
if (auditButton == null || statusBadge == null) {
    fail('audit button / status badge not found by name.'); diag('RESULT: FAIL'); return
}

def fireAudit = {
    // Wait for the button to be re-enabled (previous audit finished), then click it.
    long deadline = System.currentTimeMillis() + 10000
    while (System.currentTimeMillis() < deadline && !(boolean) readOnEdt { auditButton.isEnabled() }) {
        Thread.sleep(200)
    }
    if (!(boolean) readOnEdt { auditButton.isEnabled() }) {
        throw new IllegalStateException('audit button still disabled (previous audit running)')
    }
    SwingUtilities.invokeAndWait { auditButton.doClick() }
}
def pollBadge = { String expectPrefix, int timeoutMs ->
    long deadline = System.currentTimeMillis() + timeoutMs
    String text = null
    while (System.currentTimeMillis() < deadline) {
        text = readOnEdt { statusBadge.getText() }
        if (text != null && text.startsWith(expectPrefix)) { return text }
        Thread.sleep(250)
    }
    return text
}

final File TBOX_DIR = new File(pluginDir, 'tbox')
final File TBOX_FILE = new File(TBOX_DIR, 'it5-disjoint.ttl')
final File BAD_SHAPES = new File(LOG_DIR, 'it5-violating-shapes.ttl')

try {
    // --- Phase A: TBox-driven inconsistency ----------------------------------------
    TBOX_DIR.mkdirs()
    TBOX_FILE.setText('''@prefix owl:  <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix sumo: <http://www.ontologyportal.org/SUMO.owl#> .
@prefix uaf:  <http://purl.org/uaf/ontology#> .
# EchoBase(MilitaryBase) hasPart IonCannonControl => EchoBase is inferred a Vehicle,
# which is disjoint with MilitaryBase => provable inconsistency.
uaf:hasPart rdfs:domain sumo:Vehicle .
sumo:Vehicle owl:disjointWith sumo:MilitaryBase .
''', 'UTF-8')
    diag('TBox dropped: ' + TBOX_FILE)

    long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    fireAudit()
    def lineA = waitForJournalLine(mark, ['| AUDIT |', 'tboxFiles=1'], 30000)
    if (lineA == null) {
        fail('Phase A: no AUDIT event with the TBox within 30s')
    } else {
        diag('Phase A audit: ' + lineA.trim())
        if (!lineA.contains('consistent=false')) { fail('Phase A: disjointness TBox did not cause inconsistency') }
        else { diag('Phase A OK: inconsistency detected') }
    }
    def badgeA = pollBadge('STATUS: INCONSISTENT', 5000)
    if (badgeA?.startsWith('STATUS: INCONSISTENT')) { diag('Phase A badge OK: ' + badgeA) }
    else { fail('Phase A badge: ' + badgeA) }

    // --- Phase B: SHACL violations ---------------------------------------------------
    TBOX_FILE.delete()
    BAD_SHAPES.setText('''@prefix sh:  <http://www.w3.org/ns/shacl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix uaf: <http://purl.org/uaf/ontology#> .
uaf:ImpossibleShape a sh:NodeShape ;
    sh:targetSubjectsOf rdf:type ;
    sh:property [
        sh:path uaf:certificationEvidence ;
        sh:minCount 1 ;
        sh:message "IT5 synthetic constraint: certification evidence required." ;
    ] .
''', 'UTF-8')
    System.setProperty('semantic.plugin.shapes', BAD_SHAPES.getAbsolutePath())
    diag('shapes override set: ' + BAD_SHAPES)

    mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    fireAudit()
    def lineB = waitForJournalLine(mark, ['| AUDIT |', 'it5-violating-shapes.ttl'], 30000)
    if (lineB == null) {
        fail('Phase B: no AUDIT event using the violating shapes within 30s')
    } else {
        diag('Phase B audit: ' + lineB.trim())
        def m = (lineB =~ /shaclViolations=(\d+)/)
        int violations = m.find() ? Integer.parseInt(m.group(1)) : -1
        if (violations > 0) { diag('Phase B OK: ' + violations + ' violation(s) reported') }
        else { fail('Phase B: expected >0 SHACL violations, got ' + violations) }
        if (!lineB.contains('certification evidence required')) {
            fail('Phase B: violation detail message missing from journal')
        }
    }
    def badgeB = pollBadge('STATUS: ', 5000)
    if (badgeB != null && badgeB.contains('VIOLATION')) { diag('Phase B badge OK: ' + badgeB) }
    else { fail('Phase B badge: ' + badgeB) }

    // --- Phase C: cleanup and green re-audit ----------------------------------------
    System.clearProperty('semantic.plugin.shapes')
    if (TBOX_FILE.exists()) { TBOX_FILE.delete() }
    mark = JOURNAL.exists() ? JOURNAL.length() : 0L
    fireAudit()
    def lineC = waitForJournalLine(mark, ['| AUDIT |', 'consistent=true', 'shaclViolations=0'], 30000)
    if (lineC == null) {
        fail('Phase C: audit did not return to green after cleanup')
    } else {
        diag('Phase C OK: ' + lineC.trim())
    }
    def badgeC = pollBadge('STATUS: CONSISTENT', 5000)
    if (badgeC == 'STATUS: CONSISTENT') { diag('Phase C badge OK: ' + badgeC) }
    else { fail('Phase C badge: ' + badgeC) }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT5', t)
    pass = false
} finally {
    // Never leave synthetic constraints behind, even on failure.
    try { System.clearProperty('semantic.plugin.shapes') } catch (Throwable ignored) {}
    try { if (TBOX_FILE.exists()) { TBOX_FILE.delete() } } catch (Throwable ignored) {}
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT5 DONE ===')
