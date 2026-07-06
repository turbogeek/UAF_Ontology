// ProbeFreshMount.groovy — mount the freshly-regenerated profile (with
// setStandardSystemProfile) into a NEW blank project and check it mounts READ-ONLY (the
// "Semantic Alignment Profile" package is NOT editable), matching UAF/SoaML. Uses the git
// build output directly so no redeploy is needed to test the authoring change.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeFreshMount.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

final File MODULE = new File('E:\\_Documents\\git\\UAF_Ontology\\plugin\\profiles', 'Semantic Alignment Profile.mdzip')

diag('=== probe-fresh-mount START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    SwingUtilities.invokeAndWait {
        def prj = null
        try {
            prj = pm.createProject()
            diag('fresh project: ' + prj.getName())
            def model = prj.getPrimaryModel()

            diag('mounting: ' + MODULE + ' exists=' + MODULE.exists())
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(MODULE.toURI())
            def MS = com.nomagic.magicdraw.core.modules.ModulesService
            // importModule mounts a module READ-ONLY (reference), unlike useModule (editable use).
            def attached = MS.findOrLoadModule(prj, desc, true)
            diag('findOrLoadModule -> ' + attached)
            if (attached != null) {
                MS.importModuleOnTask(prj.getPrimaryProject(), attached)
                diag('importModuleOnTask done')
            } else {
                def used = pm.useModule(prj, desc)
                diag('fallback useModule -> ' + used)
            }

            def sa = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            check('SemanticAlignment stereotype resolves', sa != null)
            if (sa != null) {
                check('stereotype read-only', !sa.isEditable())
                check('stereotype invisible', StereotypesHelper.isInvisible(sa))
            }
            // The mounted "Semantic Alignment Profile" package must be READ-ONLY, like UAF.
            def prof = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'Semantic Alignment Profile' }
            diag('profile pkg: ' + (prof == null ? 'absent(!)' : (prof.getClass().getSimpleName() + ' editable=' + prof.isEditable())))
            check('profile package present after mount', prof != null)
            check('profile package is READ-ONLY (mounts like UAF, not embedded)', prof != null && !prof.isEditable())

            // no stray editable diagram crossing in
            def stray = model.getOwnedElement().find {
                it.getClass().getSimpleName() == 'DiagramImpl' && it.respondsTo('isEditable') && it.isEditable()
                        && it.respondsTo('getName') && (it.getName() ?: '').toLowerCase().contains('customization')
            }
            check('no stray editable Customization diagram crossed in', stray == null)

            pm.closeProjectNoSave(prj)
        } catch (Throwable t) {
            diagT('probe threw', t); fails << 'exception'
            try { if (prj != null) pm.closeProjectNoSave(prj) } catch (Throwable ignored) {}
        }
    }
    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== probe-fresh-mount DONE ===')
