// ProbeMountSaved.groovy — does mounting the SAVED PROJECT (full project with the profile
// package, not an exportModule output) resolve SemanticAlignment? If yes, ship the saved
// project as the profile and skip the broken exportModule.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeMountSaved.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-mount-saved START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    SwingUtilities.invokeAndWait {
        try {
            def prj = pm.createProject()
            diag('project: ' + prj.getName())
            int b = StereotypesHelper.getAllStereotypes(prj).size()
            def f = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Saved.mdzip')
            diag('mount: ' + f + ' exists=' + f.exists() + ' (' + (f.exists()?f.length():0) + ' bytes)')
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(f.toURI())
            boolean used = pm.useModule(prj, desc)
            diag('useModule: ' + used)
            Thread.sleep(1500)
            int a = StereotypesHelper.getAllStereotypes(prj).size()
            diag('stereotype count ' + b + ' -> ' + a + ' (delta ' + (a-b) + ')')
            def st = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            diag('SemanticAlignment resolvable: ' + (st != null))
            if (st != null) {
                diag('  qn=' + st.getQualifiedName() + ' tags=' + st.getOwnedAttribute().collect{it.getName()})
            } else {
                diag('  named *Semantic*: ' + StereotypesHelper.getAllStereotypes(prj).collect{it.getName()}.findAll{it=~/(?i)semantic/})
            }
            pm.closeProjectNoSave(prj)
            diag('RESULT: ' + (st != null ? 'PASS' : 'FAIL'))
        } catch (Throwable t) { diagT('threw', t); diag('RESULT: FAIL') }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-mount-saved DONE ===')
