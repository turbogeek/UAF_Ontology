// ProbeAutoAttachNew.groovy — close the sample, create a NEW blank project, and check
// whether mandatory.profiles auto-attaches SemanticAlignment into a fresh project (the
// case mandatory.profiles is designed for). Isolates "existing saved project" as the
// confound vs a genuinely non-attachable module.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeAutoAttachNew.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-auto-attach-new START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()

def baseline = new HashSet(Arrays.asList(java.awt.Window.getWindows()))
def watcher = new javax.swing.Timer(150, { e ->
    java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog && !baseline.contains(it) }.each {
        try { it.setVisible(false); it.dispose() } catch (Throwable ignored) {}
    }
} as java.awt.event.ActionListener)
watcher.start()

def resolved = new AtomicReference(false)
try {
    SwingUtilities.invokeAndWait {
        try {
            if (pm.getActiveProject() != null) {
                pm.closeProjectNoSave(pm.getActiveProject())
                diag('closed active project')
            }
        } catch (Throwable t) { diag('close failed: ' + t) }
    }
    Thread.sleep(1500)
    SwingUtilities.invokeAndWait {
        try {
            def prj = pm.createProject()
            if (prj == null) { diag('createProject returned null'); return }
            diag('new project: ' + prj.getName())
            def stereo = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            if (stereo != null) {
                diag('AUTO-ATTACHED into new project: ' + stereo.getQualifiedName())
                diag('isInvisible=' + StereotypesHelper.isInvisible(stereo)
                        + ' tags=' + stereo.getOwnedAttribute().collect { it.getName() }
                        + ' extends=' + StereotypesHelper.getBaseClasses(stereo).collect { it.getName() })
                resolved.set(true)
            } else {
                diag('NOT auto-attached into a new project either.')
            }
            pm.closeProjectNoSave(prj)
        } catch (Throwable t) { diagT('new-project probe threw', t) }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) } finally { watcher.stop() }

diag('RESULT: ' + (resolved.get() ? 'PASS' : 'FAIL'))
diag('=== probe-auto-attach-new DONE ===')
