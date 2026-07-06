// ProbeSetReadOnly.groovy — prove that mounting the Semantic Alignment Profile and then
// forcing it READ-ONLY (ModulesService.setReadOnlyOnTask over the module's ModuleUsages)
// makes the mounted "Semantic Alignment Profile" package non-editable, matching how UAF /
// SoaML profiles mount. Prototype for the fix to StereotypeManager.ensureProfileAvailable.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.core.modules.ModulesService
import com.nomagic.magicdraw.core.modules.ModuleUsage
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeSetReadOnly.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

def pkgEditable = { model ->
    def pkg = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == 'Semantic Alignment Profile' }
    return pkg == null ? 'absent' : pkg.isEditable()
}

diag('=== probe-set-readonly START ===')
def app = Application.getInstance()
try {
    SwingUtilities.invokeAndWait {
        try {
            def prj = app.getProject()
            if (prj == null) { diag('no project'); return }
            def model = prj.getPrimaryModel()
            diag('package editable BEFORE: ' + pkgEditable(model))

            def file = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Profile.mdzip')
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(file.toURI())
            def attached = ModulesService.findOrLoadModule(prj, desc, false)
            diag('findOrLoadModule -> ' + attached)
            if (attached == null) { diag('module not attached; cannot set read-only'); return }

            def usages = ModuleUsage.createUsages(attached, false)
            diag('module usages: ' + (usages == null ? 'null' : usages.size()))
            ModulesService.setReadOnlyOnTask(usages, true)
            diag('setReadOnlyOnTask(true) done')

            diag('package editable AFTER: ' + pkgEditable(model))
        } catch (Throwable t) {
            diagT('probe threw', t)
        }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-set-readonly DONE ===')
