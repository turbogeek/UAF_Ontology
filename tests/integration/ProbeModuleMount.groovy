// ProbeModuleMount.groovy — diagnostic (not a test)
// Determines WHY ensureProfileAvailable hung: does useModule show a modal dialog?
// Mounts on a background thread so this script stays responsive, polls for dialogs,
// and reports what it finds (dismissing nothing - diagnosis only).
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeModuleMount.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-module-mount START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); return }
def profileFile = new File('E:\\Magic SW\\CMSoS26xR1pr\\plugins\\com.nomagic.magicdraw.plugins.semantic\\profiles', 'Semantic Alignment Profile.mdzip')
diag('profile file exists=' + profileFile.exists() + ' size=' + profileFile.length())

def result = new AtomicReference('pending')
def worker = new Thread({
    try {
        def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(profileFile.toURI())
        diag('descriptor created: ' + descriptor)
        // Try on the EDT via invokeLater (non-blocking) and capture outcome
        SwingUtilities.invokeLater {
            try {
                boolean used = app.getProjectsManager().useModule(project, descriptor)
                diag('useModule returned: ' + used)
                result.set('used=' + used)
            } catch (Throwable t) {
                diagT('useModule threw', t)
                result.set('threw')
            }
        }
    } catch (Throwable t) {
        diagT('descriptor/setup threw', t)
        result.set('setup-threw')
    }
}, 'probe-mount')
worker.setDaemon(true)
worker.start()

// Poll for dialogs while the mount is in flight
for (int i = 0; i < 20; i++) {
    Thread.sleep(500)
    def dialogs = []
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
            dialogs << ((dlg.respondsTo('getTitle') ? dlg.getTitle() : '?') + ' modal=' + dlg.isModal())
        }
    }
    if (!dialogs.isEmpty()) {
        diag('DIALOG(S) VISIBLE at t=' + (i * 500) + 'ms: ' + dialogs)
        // enumerate buttons so we know how to auto-dismiss in the real flow
        SwingUtilities.invokeAndWait {
            java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
                def btns = []
                def collect
                collect = { java.awt.Component c -> if (c instanceof javax.swing.AbstractButton) { btns << c.getText() }; if (c instanceof java.awt.Container) { c.getComponents().each { collect(it) } } }
                collect(dlg)
                diag('  buttons: ' + btns)
            }
        }
        break
    }
    if (result.get() != 'pending') { diag('mount completed without dialog: ' + result.get()); break }
}
diag('final result: ' + result.get())
def stereo = com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotype(project, 'SemanticAlignment')
diag('stereotype resolvable now: ' + (stereo != null))
diag('=== probe-module-mount DONE ===')
