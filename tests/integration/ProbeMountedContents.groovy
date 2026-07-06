// ProbeMountedContents.groovy — after a successful useModule, what stereotypes/profiles
// does the module actually contain? Determines whether SemanticAlignment is even in the
// exported .mdzip (and under what name/profile) vs a timing/resolution issue.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeMountedContents.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-mounted-contents START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
def before = new AtomicReference(0)
try {
    SwingUtilities.invokeAndWait {
        try {
            def prj = pm.createProject()
            diag('project: ' + prj.getName())
            int b = StereotypesHelper.getAllStereotypes(prj).size()
            diag('stereotypes before mount: ' + b)
            def f = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Profile.mdzip')
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(f.toURI())
            boolean used = pm.useModule(prj, desc)
            diag('useModule: ' + used)
            Thread.sleep(1500)
            def all = StereotypesHelper.getAllStereotypes(prj)
            diag('stereotypes after mount: ' + all.size() + ' (delta ' + (all.size() - b) + ')')
            // Show any NEW stereotypes (from the mounted module)
            def names = all.collect { it.getName() }
            diag('any named *Semantic*: ' + names.findAll { it =~ /(?i)semantic/ })
            // Also list profiles/packages owned at top level
            diag('top-level packages: ' + prj.getPrimaryModel().getOwnedElement()
                    .findAll { it.respondsTo('getName') && it.getName() }
                    .collect { it.getName() }.take(20))
            // Directly probe by profile name
            def st = StereotypesHelper.getStereotype(prj, 'SemanticAlignment', 'Semantic Alignment Profile')
            diag('getStereotype(name,profile): ' + (st != null))
            pm.closeProjectNoSave(prj)
        } catch (Throwable t) { diagT('probe threw', t) }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-mounted-contents DONE ===')
