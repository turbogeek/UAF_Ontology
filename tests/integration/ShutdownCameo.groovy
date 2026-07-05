// ShutdownCameo.groovy — utility (not a test)
// Gracefully closes the active project WITHOUT saving (integration fixtures must not be
// persisted), then requests application shutdown via the official OpenAPI - the
// programmatic equivalent of File > Exit. No System.exit anywhere.
import com.nomagic.magicdraw.core.Application

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ShutdownCameo.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

def app = Application.getInstance()
def pm = app.getProjectsManager()

SwingUtilities.invokeAndWait {
    try {
        if (pm.getActiveProject() != null) {
            if (pm.respondsTo('closeProjectNoSave')) {
                pm.closeProjectNoSave()
                diag('project closed without saving (ProjectsManager.closeProjectNoSave)')
            } else {
                pm.closeProject()
                diag('project closed (ProjectsManager.closeProject)')
            }
        } else {
            diag('no active project to close')
        }
    } catch (Throwable t) {
        diag('project close failed: ' + t)
    }
}

// Shutdown is deferred so the harness can flush this run's HTTP response first.
Thread shutdownThread = new Thread({
    Thread.sleep(1200)
    diag('requesting application shutdown...')
    SwingUtilities.invokeLater {
        try {
            app.shutdown()
        } catch (Throwable t) {
            // ApplicationExitedException is the NORMAL signal of a clean exit
            diag('shutdown path: ' + t.getClass().getSimpleName())
        }
    }
} as Runnable, 'it-shutdown-cameo')
shutdownThread.setDaemon(true)
shutdownThread.start()
diag('shutdown scheduled')
