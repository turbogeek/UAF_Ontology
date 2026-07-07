// ProbeImportModule.groovy — decisive experiment: does importModule resolve the
// stereotype where useModule fails with "Module not found"? Import merges content into
// the project (resolving Element against the target's own metamodel) rather than
// referencing the source project's standard profile.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File MODULE = new File('E:\\Magic SW\\CMSoS26xR1pr\\plugins\\com.nomagic.magicdraw.plugins.semantic\\profiles', 'Semantic Alignment Profile.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeImportModule.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-import-module START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); return }

def outcome = new AtomicReference('pending')
def worker = new Thread({
    try {
        def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(MODULE.toURI())
        long start = System.currentTimeMillis()
        app.getProjectsManager().importModule(project, descriptor)
        diag('importModule returned in ' + (System.currentTimeMillis() - start) + 'ms')
        outcome.set('done')
    } catch (Throwable t) { diagT('importModule threw', t); outcome.set('threw') }
}, 'probe-import')
worker.setDaemon(true)
worker.start()

for (int i = 0; i < 30; i++) {
    Thread.sleep(500)
    def infos = []
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
            infos << ('"' + (dlg.respondsTo('getTitle') ? dlg.getTitle() : '?') + '"')
        }
    }
    if (!infos.isEmpty()) { diag('DIALOG at t=' + (i*500) + 'ms: ' + infos); break }
    if (outcome.get() != 'pending') { diag('completed: ' + outcome.get()); break }
}
Thread.sleep(1000)
def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
diag('stereotype resolvable after import: ' + (stereo != null))
if (stereo != null) {
    def tags = stereo.getOwnedAttribute().collect { it.getName() }
    def bases = StereotypesHelper.getBaseClasses(stereo).collect { it.getName() }
    diag('tags=' + tags + ' extends=' + bases)
    diag('RESULT: ' + (tags.contains('mappedConceptURI') && bases.contains('Element') ? 'PASS' : 'PARTIAL'))
} else {
    diag('RESULT: FAIL')
}
diag('=== probe-import-module DONE ===')
