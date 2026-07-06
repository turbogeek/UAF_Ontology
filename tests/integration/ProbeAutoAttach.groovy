// ProbeAutoAttach.groovy — did mandatory.profiles auto-attach SemanticAlignment into
// the open project at load, with NO mount call? Also check invisibility + tags.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeAutoAttach.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== probe-auto-attach START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); diag('RESULT: FAIL'); return }
diag('project: ' + project.getName())

def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
if (stereo != null) {
    diag('AUTO-ATTACHED: SemanticAlignment resolvable with NO mount call -> ' + stereo.getQualifiedName())
    diag('isInvisible: ' + StereotypesHelper.isInvisible(stereo))
    diag('tags: ' + stereo.getOwnedAttribute().collect { it.getName() })
    def bases = StereotypesHelper.getBaseClasses(stereo).collect { it.getName() }
    diag('extends metaclasses: ' + bases)
    diag('RESULT: ' + (bases.contains('Element') ? 'PASS' : 'PARTIAL'))
} else {
    // list attached modules to see if the profile loaded at all
    diag('SemanticAlignment NOT resolvable. Attached modules:')
    try {
        def pm = app.getProjectsManager()
        pm.getAvailableDescriptorsForProject(project).each { diag('  module: ' + it.getRepresentationString()) }
    } catch (Throwable t) { diag('  (could not list modules: ' + t + ')') }
    diag('RESULT: FAIL')
}
diag('=== probe-auto-attach DONE ===')
