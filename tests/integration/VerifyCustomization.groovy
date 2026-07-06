// VerifyCustomization.groovy — confirms the plugin creates an INVISIBLE SemanticAlignment
// stereotype with a «Customization» (hideMetatype=true) targeting it, in the open project.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'VerifyCustomization.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== verify-customization START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { fail('no project'); diag('RESULT: FAIL'); return }
diag('project: ' + project.getName())

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
def cl = plugin.getClass().getClassLoader()
def mgrCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, cl)
def projCls = Class.forName('com.nomagic.magicdraw.core.Project', true, cl)

try {
    // Trigger profile+customization creation (module mount fails -> in-project fallback)
    def okHolder = new AtomicReference()
    def worker = new Thread({ okHolder.set(mgrCls.getMethod('ensureProfileAvailable', projCls).invoke(null, project)) }, 'ensure')
    worker.setDaemon(true); worker.start(); worker.join(20000)
    diag('ensureProfileAvailable -> ' + okHolder.get())

    def stereo = StereotypesHelper.getStereotype(project, 'SemanticAlignment')
    if (stereo == null) { fail('SemanticAlignment stereotype not created'); diag('RESULT: FAIL'); return }
    diag('stereotype OK: ' + stereo.getQualifiedName() + ' isInvisible=' + StereotypesHelper.isInvisible(stereo))

    // Find the «Customization» element targeting our stereotype
    def custStereo = StereotypesHelper.getStereotype(project, 'Customization')
    if (custStereo == null) { fail('«Customization» stereotype unavailable'); diag('RESULT: FAIL'); return }
    def found = new AtomicReference()
    def visit
    visit = { el ->
        if (found.get() != null) { return }
        try {
            if (StereotypesHelper.hasStereotype(el, custStereo)) {
                def tgt = StereotypesHelper.getStereotypePropertyValue(el, custStereo, 'customizationTarget')
                if (tgt != null && tgt.any { it == stereo }) { found.set(el) }
            }
        } catch (Throwable ignored) {}
        el.getOwnedElement().each { visit(it) }
    }
    SwingUtilities.invokeAndWait { visit(project.getPrimaryModel()) }
    def cust = found.get()
    if (cust == null) { fail('no «Customization» targeting SemanticAlignment found') }
    else {
        diag('customization element: ' + (cust.respondsTo('getName') ? cust.getName() : cust.getHumanName()))
        def hide = StereotypesHelper.getStereotypePropertyValue(cust, custStereo, 'hideMetatype')
        def rep = StereotypesHelper.getStereotypePropertyValue(cust, custStereo, 'representationText')
        def cat = StereotypesHelper.getStereotypePropertyValue(cust, custStereo, 'category')
        def spec = StereotypesHelper.getStereotypePropertyValue(cust, custStereo, 'standardExpertConfiguration')
        diag('  hideMetatype=' + hide + ' representationText=' + rep + ' category=' + cat)
        diag('  standardExpertConfiguration entries=' + (spec == null ? 0 : spec.size()))
        if (!(hide != null && hide.contains(true))) { fail('hideMetatype not true -> label would show') }
        else { diag('INVISIBLE mechanism OK: hideMetatype=true') }
        if (spec == null || spec.size() != 3) { fail('expected 3 Specification property entries, got ' + (spec == null ? 0 : spec.size())) }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t); pass = false }

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== verify-customization DONE ===')
