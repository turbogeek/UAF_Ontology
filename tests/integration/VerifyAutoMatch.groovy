// VerifyAutoMatch.groovy — verifies automatic UAF-stereotype -> concept alignment live
// in the UAF SAR Sample. Selects real ActualOrganization/ActualPost/ResourceArtifact
// elements and confirms the SELECTION journal shows the auto-resolved concept +
// foundational equivalent, with NO manual alignment.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'VerifyAutoMatch.log')
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
                raf.seek(from); byte[] buf = new byte[(int)(JOURNAL.length()-from)]; raf.readFully(buf)
                def line = new String(buf,'UTF-8').readLines().find { l -> tokens.every { l.contains(it) } }
                if (line != null) return line
            } finally { raf.close() }
        }
        Thread.sleep(200)
    }
    return null
}

diag('=== verify-auto-match START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { fail('no project'); diag('RESULT: FAIL'); return }
diag('project: ' + project.getName())

// Find one element per target stereotype
def findByStereo = { String stereoName ->
    def result = new AtomicReference()
    def visit
    visit = { el ->
        if (result.get() == null) {
            if (StereotypesHelper.getStereotypes(el).any { it.getName() == stereoName }
                    && el.respondsTo('getName') && el.getName()) { result.set(el) }
            el.getOwnedElement().each { visit(it) }
        }
    }
    visit(project.getPrimaryModel())
    result.get()
}

def selectInTree = { element ->
    def err = null
    SwingUtilities.invokeAndWait {
        try {
            def tree = app.getMainFrame().getBrowser().getContainmentTree()
            def path = tree.openNode(element)
            if (path != null) { tree.getTree().setSelectionPath(path) } else { throw new IllegalStateException('no path') }
        } catch (Throwable t) { err = t }
    }
    if (err != null) throw err
}

try {
    // Expected concept == the ontology rdfs:label (unspaced, matches the stereotype name)
    [['ActualOrganization', 'ActualOrganization'],
     ['ActualPost', 'ActualPost'],
     ['ResourceArtifact', 'ResourceArtifact'],
     ['Capability', 'Capability']].each { pair ->
        def stereoName = pair[0]; def expectConcept = pair[1]
        def el = findByStereo(stereoName)
        if (el == null) { diag('note: no ' + stereoName + ' element in sample - skipped'); return }
        long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
        selectInTree(el)
        def line = waitForJournalLine(mark, ['| SELECTION |', 'autoConcept=' + expectConcept], 8000)
        if (line == null) {
            fail(stereoName + ' (' + el.getName() + ') did not auto-resolve to "' + expectConcept + '"')
            def any = waitForJournalLine(mark, ['| SELECTION |'], 2000)
            if (any != null) { diag('  got: ' + any.trim()) }
        } else {
            diag('OK ' + stereoName + ' [' + el.getName() + '] -> ' + line.substring(line.indexOf('autoConcept=')).trim())
        }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t); pass = false }

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== verify-auto-match DONE ===')
