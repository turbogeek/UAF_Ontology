// OpenScratchProject.groovy — utility (not a test).
// The GUI integration suite (IT1+) requires an open project. When Cameo is relaunched
// headlessly (e.g. after a deploy) no project is open, so this creates a blank one if
// needed. Idempotent: reuses an already-open project.
import com.nomagic.magicdraw.core.Application
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'OpenScratchProject.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== open-scratch-project START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
def name = new String[1]
try {
    SwingUtilities.invokeAndWait {
        if (app.getProject() != null) {
            diag('project already open: ' + app.getProject().getName())
        } else {
            def prj = pm.createProject()
            diag('created project: ' + (prj == null ? 'null' : prj.getName()))
        }
        name[0] = (app.getProject() == null) ? null : app.getProject().getName()
    }
    diag('RESULT: ' + (name[0] != null ? 'PASS' : 'FAIL'))
} catch (Throwable t) {
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag('threw\n' + sw.toString())
    diag('RESULT: FAIL')
}
diag('=== open-scratch-project DONE ===')
