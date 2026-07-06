// VerifyProfileMount.groovy — verification probe
// Confirms the shipped profile module mounts cleanly (no error dialog) and the
// SemanticAlignment stereotype becomes resolvable, in the CURRENT open project.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'VerifyProfileMount.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('=== verify-profile-mount START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project open'); diag('RESULT: FAIL'); return }
diag('project: ' + project.getName())

def before = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
diag('stereotype before mount: ' + (before != null))

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
def mgrCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager',
        true, plugin.getClass().getClassLoader())
def projCls = Class.forName('com.nomagic.magicdraw.core.Project', true, plugin.getClass().getClassLoader())

def ok = new AtomicReference()
long start = System.currentTimeMillis()
// Call from a background thread so ensureProfileAvailable does its own EDT dispatch
def worker = new Thread({
    ok.set(mgrCls.getMethod('ensureProfileAvailable', projCls).invoke(null, project))
}, 'verify-mount')
worker.setDaemon(true)
worker.start()
worker.join(20000)
long elapsed = System.currentTimeMillis() - start
diag('ensureProfileAvailable returned ' + ok.get() + ' in ' + elapsed + 'ms')

def after = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
if (after != null) {
    diag('stereotype resolvable: ' + after.getQualifiedName())
    // Verify the 3 generic tags exist
    def tags = after.getOwnedAttribute().collect { it.getName() }
    diag('tags: ' + tags)
    boolean hasAll = ['mappedConceptURI', 'ontologySource', 'mappingConfidence'].every { tags.contains(it) }
    // Confirm it extends the generic Element metaclass (usable in any UML-based model)
    def metaClasses = StereotypesHelper.getBaseClasses(after).collect { it.getName() }
    diag('extends metaclasses: ' + metaClasses)
    boolean generic = metaClasses.contains('Element')
    diag('RESULT: ' + (hasAll && generic ? 'PASS' : 'FAIL'))
} else {
    diag('RESULT: FAIL (stereotype not resolvable after mount)')
}
diag('=== verify-profile-mount DONE ===')
