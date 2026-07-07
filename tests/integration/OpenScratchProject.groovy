// OpenScratchProject.groovy — utility (not a test).
// The GUI integration suite (IT1+) requires an open project. When Cameo is relaunched
// headlessly (e.g. after a deploy) no project is open, so this creates a blank one if
// needed. Idempotent: reuses an already-open project.
import com.nomagic.magicdraw.core.Application
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final File LOG = new File(LOG_DIR, 'OpenScratchProject.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== open-scratch-project START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
def name = new String[1]
try {
    if (app.getProject() == null) {
        // Fire NON-blocking: createProject shows a progress dialog on the EDT that would
        // deadlock/interrupt an invokeAndWait. Poll for the project, then let it settle.
        SwingUtilities.invokeLater { try { pm.createProject() } catch (Throwable ignored) {} }
        for (int i = 0; i < 75 && app.getProject() == null; i++) { Thread.sleep(200) }
        Thread.sleep(2500)
    } else {
        diag('project already open: ' + app.getProject().getName())
    }
    name[0] = (app.getProject() == null) ? null : app.getProject().getName()
    diag('project: ' + (name[0] == null ? 'null' : name[0]))
    diag('RESULT: ' + (name[0] != null ? 'PASS' : 'FAIL'))
} catch (Throwable t) {
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag('threw\n' + sw.toString())
    diag('RESULT: FAIL')
}
diag('=== open-scratch-project DONE ===')
