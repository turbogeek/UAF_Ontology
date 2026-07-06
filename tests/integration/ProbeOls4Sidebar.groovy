// ProbeOls4Sidebar.groovy — live-verify the OLS4 wiring: type a term in the sidebar search
// box, click "Search OLS4 (online)", and confirm remote candidates append to the suggestion
// list (journal TERMSOURCE event + list entries with OLS4 ontology ids). Needs an open
// project + network to the EBI OLS4 API. Read-only (no model writes, no save).
import com.nomagic.magicdraw.core.Application
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeOls4Sidebar.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

final String QUERY = 'Weather'
final File JOURNAL = new File(new File(System.getProperty('user.home'), '.semantic_alignment_plugin'), 'semantic-plugin.log')
def waitForJournal = { long from, List<String> tokens, int timeoutMs ->
    long deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (JOURNAL.exists() && JOURNAL.length() > from) {
            def raf = new RandomAccessFile(JOURNAL, 'r')
            try {
                raf.seek(from); byte[] buf = new byte[(int) (JOURNAL.length() - from)]; raf.readFully(buf)
                def line = new String(buf, 'UTF-8').readLines().find { l -> tokens.every { l.contains(it) } }
                if (line != null) { return line }
            } finally { raf.close() }
        }
        Thread.sleep(250)
    }
    return null
}
def findByName = { String name ->
    def result = new AtomicReference()
    def walk
    walk = { java.awt.Component c -> if (result.get() != null) { return }
        if (name == c.getName()) { result.set(c); return }
        if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } } }
    SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (result.get() == null) { walk(w) } } }
    return result.get()
}

diag('=== probe-ols4-sidebar START ===')
def app = Application.getInstance()
if (app.getProject() == null) { diag('no project open'); diag('RESULT: FAIL'); return }
def searchField = findByName('semantic.conceptField')
def ols4Button = findByName('semantic.ols4Button')
def suggestionList = findByName('semantic.suggestionList')
check('search field found', searchField != null)
check('OLS4 button found', ols4Button != null)
check('suggestion list found', suggestionList != null)
if (searchField == null || ols4Button == null || suggestionList == null) { diag('RESULT: FAIL ' + fails); return }

long mark = JOURNAL.exists() ? JOURNAL.length() : 0L
SwingUtilities.invokeAndWait { searchField.setText(QUERY); ols4Button.doClick() }
diag('clicked OLS4 for "' + QUERY + '"; awaiting network...')
def line = waitForJournal(mark, ['| TERMSOURCE |', "OLS4 '" + QUERY + "'"], 25000)
if (line == null) {
    check('OLS4 TERMSOURCE journal event fired', false)
} else {
    diag('journal: ' + line.trim())
    check('OLS4 TERMSOURCE journal event fired', true)
    check('OLS4 returned candidates (appended > 0)', line.contains('appended') && !line.contains('-> 0'))
}
// Inspect the suggestion list for OLS4-sourced rows (ontologyId starts with "OLS4:").
def ols4Rows = new AtomicReference(0)
SwingUtilities.invokeAndWait {
    def m = suggestionList.getModel(); int n = 0
    for (int i = 0; i < m.getSize(); i++) {
        def cs = m.getElementAt(i)
        try { if ((cs.entry().ontologyId() ?: '').startsWith('OLS4:')) { n++ } } catch (Throwable ignored) {}
    }
    ols4Rows.set(n)
}
diag('OLS4 rows in suggestion list: ' + ols4Rows.get())
check('at least one OLS4 row appended to the list', (ols4Rows.get() as int) > 0)

diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
diag('=== probe-ols4-sidebar DONE ===')
