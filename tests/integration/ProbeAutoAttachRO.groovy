// ProbeAutoAttachRO.groovy — does the now-standard-profile-flagged Semantic Alignment
// Profile AUTO-ATTACH (read-only) to a project WITHOUT an explicit useModule, the way the
// stock profiles (UAF/SoaML) are always present? Tests two paths: (a) a fresh createProject,
// (b) StandardProfilesHelper.findLoadModuleOnDemand if available. Checks whether the
// SemanticAlignment stereotype resolves and, if so, whether its package is read-only.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeAutoAttachRO.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

def pkgState = { model ->
    def pkg = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'Semantic Alignment Profile' }
    return pkg == null ? 'absent' : ('present editable=' + pkg.isEditable())
}

diag('=== probe-auto-attach-ro START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    SwingUtilities.invokeAndWait {
        def prj = null
        try {
            prj = pm.createProject()
            def model = prj.getPrimaryModel()
            diag('fresh project: ' + prj.getName())

            // (a) Without any useModule - did standardProfile auto-attach fire?
            def sa0 = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            diag('WITHOUT useModule: SemanticAlignment=' + (sa0 == null ? 'null' : 'RESOLVES')
                    + '  pkg=' + pkgState(model))

            // (b) Try StandardProfilesHelper on-demand load, if the API exists.
            try {
                def SPH = Class.forName('com.nomagic.magicdraw.uml2.util.StandardProfilesHelper')
                diag('StandardProfilesHelper methods: ' + SPH.getMethods().findAll {
                    java.lang.reflect.Modifier.isStatic(it.getModifiers()) }.collect { it.getName() }.unique().sort())
            } catch (Throwable t) {
                try {
                    def SPH = Class.forName('com.nomagic.magicdraw.core.project.StandardProfilesHelper')
                    diag('StandardProfilesHelper(core) methods: ' + SPH.getMethods().findAll {
                        java.lang.reflect.Modifier.isStatic(it.getModifiers()) }.collect { it.getName() }.unique().sort())
                } catch (Throwable t2) { diag('StandardProfilesHelper not found in either package') }
            }

            pm.closeProjectNoSave(prj)
        } catch (Throwable t) {
            diagT('probe threw', t)
            try { if (prj != null) pm.closeProjectNoSave(prj) } catch (Throwable ignored) {}
        }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-auto-attach-ro DONE ===')
