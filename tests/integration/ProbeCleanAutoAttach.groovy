// ProbeCleanAutoAttach.groovy — clean test (no dialog watcher, which previously killed
// createProject's progress dialog). Creates a genuinely fresh project and checks whether
// mandatory.profiles auto-attached SemanticAlignment; if not, tries a clean useModule
// from the install profiles dir.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeCleanAutoAttach.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-clean-auto-attach START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()

def outcome = new AtomicReference('?')
try {
    SwingUtilities.invokeAndWait {
        try {
            // Replicate the BuildSemanticProfile pattern that worked: createProject in one
            // invokeAndWait, NO dialog watcher.
            def prj = pm.createProject()
            if (prj == null) { diag('createProject returned null'); outcome.set('CREATE_NULL'); return }
            diag('fresh project: ' + prj.getName())

            def auto = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            if (auto != null) {
                diag('AUTO-ATTACHED via mandatory.profiles: ' + auto.getQualifiedName())
                outcome.set('AUTO_ATTACHED')
            } else {
                diag('not auto-attached; trying clean useModule from install profiles dir')
                def f = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Profile.mdzip')
                diag('mount file: ' + f + ' exists=' + f.exists())
                def desc = ProjectDescriptorsFactory.createProjectDescriptor(f.toURI())
                boolean used = pm.useModule(prj, desc)
                diag('useModule returned: ' + used)
                def st2 = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
                if (st2 != null) { diag('RESOLVED via useModule: ' + st2.getQualifiedName()); outcome.set('USEMODULE_OK') }
                else { diag('still not resolvable'); outcome.set('UNRESOLVED') }
            }
            pm.closeProjectNoSave(prj)
        } catch (Throwable t) { diagT('probe threw', t); outcome.set('THREW') }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }

diag('OUTCOME: ' + outcome.get())
diag('=== probe-clean-auto-attach DONE ===')
