// ProbeUseModule.groovy — diagnostic (not a test), NO auto-dismiss
// Calls useModule with the now-valid shared module on a background thread and reports
// exactly what happens: return value, timing, and any dialog (title/buttons) it raises.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File MODULE = new File('E:\\Magic SW\\CMSoS26xR1pr\\plugins\\com.nomagic.magicdraw.plugins.semantic\\profiles', 'Semantic Alignment Profile.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeUseModule.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-use-module START ===')
diag('module exists=' + MODULE.exists() + ' size=' + MODULE.length())
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); return }

def outcome = new AtomicReference('pending')
def worker = new Thread({
    try {
        def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(MODULE.toURI())
        diag('descriptor: ' + descriptor)
        // Try OFF the EDT first (many persistence ops prefer a non-EDT thread)
        long start = System.currentTimeMillis()
        boolean used = app.getProjectsManager().useModule(project, descriptor)
        diag('useModule(off-EDT) returned ' + used + ' in ' + (System.currentTimeMillis() - start) + 'ms')
        outcome.set('off-edt=' + used)
    } catch (Throwable t) {
        diagT('useModule off-EDT threw', t)
        outcome.set('threw')
    }
}, 'probe-usemodule')
worker.setDaemon(true)
worker.start()

for (int i = 0; i < 30; i++) {
    Thread.sleep(500)
    def infos = []
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
            def btns = []
            def collect
            collect = { java.awt.Component c -> if (c instanceof javax.swing.AbstractButton && c.getText()) { btns << c.getText() }; if (c instanceof java.awt.Container) { c.getComponents().each { collect(it) } } }
            collect(dlg)
            infos << ('"' + (dlg.respondsTo('getTitle') ? dlg.getTitle() : '?') + '" modal=' + dlg.isModal() + ' buttons=' + btns)
        }
    }
    if (!infos.isEmpty()) { diag('DIALOG at t=' + (i*500) + 'ms: ' + infos); break }
    if (outcome.get() != 'pending') { diag('completed without dialog: ' + outcome.get()); break }
}
def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
diag('stereotype resolvable: ' + (stereo != null))
diag('=== probe-use-module DONE ===')
