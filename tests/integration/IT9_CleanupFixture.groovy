// IT9_CleanupFixture.groovy
// =====================================================================================
// Integration cleanup: removes the SemanticAlignmentIT fixture package (and everything
// in it, including UndoProbe_* elements) from the open project. The semantic profile is
// left in place - it is reusable project infrastructure, not test residue.
// Safe to run repeatedly; reports PASS when the package is absent afterwards.
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager
import com.nomagic.magicdraw.openapi.uml.SessionManager

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String PKG_NAME = 'SemanticAlignmentIT'

final File LOG = new File(LOG_DIR, 'IT9_CleanupFixture.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true

diag('=== IT9 cleanup START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { diag('No open project - nothing to clean.'); diag('RESULT: PASS'); return }

try {
    def model = project.getPrimaryModel()
    def pkg = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
    if (pkg == null) {
        diag('fixture package not present - nothing to clean')
    } else {
        def sm = SessionManager.getInstance()
        def error = null
        SwingUtilities.invokeAndWait {
            sm.createSession(project, 'IT9 remove fixture')
            try {
                ModelElementsManager.getInstance().removeElement(pkg)
                sm.closeSession(project)
            } catch (Throwable t) {
                try { sm.cancelSession(project) } catch (Throwable ignored) {}
                error = t
            }
        }
        if (error != null) { throw error }
        diag('fixture package removed')
    }
    // Un-instrument the model root (remove the SemanticModel marker) - the reversible half
    // of the instrument/un-instrument workflow. The profile module itself is left mounted
    // (reusable infrastructure), matching the plugin's un-instrument behavior.
    def modelStereo = com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotype(project, 'SemanticModel')
    if (modelStereo != null
            && com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.hasStereotype(model, modelStereo)) {
        def err2 = null
        def sm2 = SessionManager.getInstance()
        SwingUtilities.invokeAndWait {
            sm2.createSession(project, 'IT9 un-instrument')
            try {
                com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.removeStereotype(model, modelStereo)
                sm2.closeSession(project)
            } catch (Throwable t) { try { sm2.cancelSession(project) } catch (Throwable ignored) {}; err2 = t }
        }
        if (err2 != null) { throw err2 }
        diag('model un-instrumented (SemanticModel removed from root)')
    }
    def still = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
    if (still != null) {
        pass = false
        diag('FAIL: fixture package still present after removal')
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT9', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT9 DONE ===')
