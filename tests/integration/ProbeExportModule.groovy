// ProbeExportModule.groovy — diagnostic (not a test)
// Builds the profile package, calls exportModule on a background thread, and captures
// whatever dialog it raises (title + buttons) WITHOUT blocking, so we learn how to
// drive it non-interactively.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File OUT = new File('E:\\_Documents\\git\\UAF_Ontology\\plugin\\profiles', 'Semantic Alignment Profile.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeExportModule.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-export-module START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()

def pkgHolder = new AtomicReference()
def project = new AtomicReference()
def err = new AtomicReference()
SwingUtilities.invokeAndWait {
    try {
        def p = pm.createProject()
        project.set(p)
        def sm = SessionManager.getInstance()
        sm.createSession(p, 'probe build profile')
        try {
            def ef = p.getElementsFactory()
            def profile = ef.createProfileInstance()
            profile.setName('Semantic Alignment Profile')
            profile.setOwner(p.getPrimaryModel())
            def metaElement = StereotypesHelper.getAllMetaClasses(p).find { it.getName() == 'Element' }
            def stereo = StereotypesHelper.createStereotype(p, 'SemanticAlignment', [metaElement])
            stereo.setOwner(profile)
            ['mappedConceptURI', 'ontologySource', 'mappingConfidence'].each { t ->
                def prop = ef.createPropertyInstance(); prop.setName(t); stereo.getOwnedAttribute().add(prop)
            }
            sm.closeSession(p)
            pkgHolder.set(profile)
            diag('profile package built')
        } catch (Throwable t) { sm.cancelSession(p); throw t }
    } catch (Throwable t) { err.set(t) }
}
if (err.get() != null) { diagT('build failed', err.get()); return }

if (OUT.exists()) { OUT.delete() }
def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(OUT.toURI())

// Fire exportModule on a background thread so this script keeps polling for dialogs
def worker = new Thread({
    try {
        pm.exportModule(project.get(), [pkgHolder.get()], 'Semantic Alignment Profile', descriptor)
        diag('exportModule returned normally')
    } catch (Throwable t) { diagT('exportModule threw', t) }
}, 'probe-export')
worker.setDaemon(true)
worker.start()

boolean sawDialog = false
for (int i = 0; i < 24; i++) {
    Thread.sleep(500)
    def infos = []
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
            def btns = []
            def collect
            collect = { java.awt.Component c -> if (c instanceof javax.swing.AbstractButton && c.getText()) { btns << c.getText() }; if (c instanceof java.awt.Container) { c.getComponents().each { collect(it) } } }
            collect(dlg)
            infos << [title: (dlg.respondsTo('getTitle') ? dlg.getTitle() : '?'), modal: dlg.isModal(), buttons: btns]
        }
    }
    if (!infos.isEmpty()) {
        sawDialog = true
        infos.each { diag('DIALOG t=' + (i*500) + 'ms title="' + it.title + '" modal=' + it.modal + ' buttons=' + it.buttons) }
        break
    }
    if (OUT.exists() && OUT.length() > 0) { diag('module file appeared without dialog at t=' + (i*500) + 'ms'); break }
}
if (!sawDialog) { diag('no dialog observed; file exists=' + OUT.exists()) }
diag('=== probe-export-module DONE (leaving project + any dialog as-is for inspection) ===')
