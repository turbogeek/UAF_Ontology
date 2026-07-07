// CaptureUseModuleError.groovy — diagnostic: reads the actual error text
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File MODULE = new File('E:\\Magic SW\\CMSoS26xR1pr\\plugins\\com.nomagic.magicdraw.plugins.semantic\\profiles', 'Semantic Alignment Profile.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'CaptureUseModuleError.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== capture-usemodule-error START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); return }

def worker = new Thread({
    try {
        def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(MODULE.toURI())
        app.getProjectsManager().useModule(project, descriptor)
    } catch (Throwable t) { diag('useModule threw: ' + t) }
}, 'cap-usemodule')
worker.setDaemon(true)
worker.start()

// Read all text from any error dialog that appears, then dismiss it
for (int i = 0; i < 20; i++) {
    Thread.sleep(500)
    boolean found = false
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
            found = true
            def texts = []
            def collect
            collect = { java.awt.Component c ->
                if (c instanceof javax.swing.JLabel && c.getText()) { texts << ('LABEL: ' + c.getText()) }
                if (c instanceof javax.swing.text.JTextComponent && c.getText()) { texts << ('TEXT: ' + c.getText().replaceAll('[\\r\\n]+', ' | ')) }
                if (c instanceof java.awt.Container) { c.getComponents().each { collect(it) } }
            }
            collect(dlg)
            diag('DIALOG "' + (dlg.respondsTo('getTitle') ? dlg.getTitle() : '?') + '":')
            texts.take(20).each { diag('  ' + it.take(300)) }
            // click OK to dismiss
            def collectBtns
            collectBtns = { java.awt.Component c -> if (c instanceof javax.swing.AbstractButton && c.getText() == 'OK') { c.doClick() }; if (c instanceof java.awt.Container) { c.getComponents().each { collectBtns(it) } } }
            collectBtns(dlg)
        }
    }
    if (found) { break }
}
diag('=== capture-usemodule-error DONE ===')
