// ProbeInstalledProfileMount.groovy — pivotal experiment
// Tests whether mounting the profile FROM the install profiles dir (shared-referenced,
// canonical location) resolves the SemanticAlignment stereotype - unlike the earlier
// plugin-dir scratch mount that failed "Module not found". Uses a fresh project.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeInstalledProfileMount.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-installed-profile-mount START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
def profilesDir = ApplicationEnvironment.getProfilesDirectory()
diag('profiles dir: ' + profilesDir)
def profileFile = new File(profilesDir, 'Semantic Alignment Profile.mdzip')
diag('profile file exists: ' + profileFile.exists() + ' (' + (profileFile.exists() ? profileFile.length() : 0) + ' bytes)')

def result = new AtomicReference('pending')
def baseline = new HashSet(Arrays.asList(java.awt.Window.getWindows()))
// auto-dismiss any error modal so we learn the outcome without hanging
def watcher = new javax.swing.Timer(150, { e ->
    java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog && !baseline.contains(it) }.each {
        diag('  (dismissing dialog: ' + (it.respondsTo('getTitle') ? it.getTitle() : '?') + ')')
        it.setVisible(false); it.dispose()
    }
} as java.awt.event.ActionListener)
watcher.start()

try {
    SwingUtilities.invokeAndWait {
        try {
            def prj = pm.createProject()
            diag('fresh project: ' + prj.getName())
            def before = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            diag('stereotype before mount: ' + (before != null))
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(profileFile.toURI())
            diag('descriptor URI: ' + desc.getURI())
            boolean used = pm.useModule(prj, desc)
            diag('useModule returned: ' + used)
            def stereo = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            if (stereo != null) {
                diag('STEREOTYPE RESOLVED: ' + stereo.getQualifiedName())
                diag('isInvisible: ' + StereotypesHelper.isInvisible(stereo))
                def tags = stereo.getOwnedAttribute().collect { it.getName() }
                diag('tags: ' + tags)
                result.set('RESOLVED')
            } else {
                diag('stereotype NOT resolved after mount')
                result.set('NOT_RESOLVED')
            }
            pm.closeProjectNoSave(prj)
        } catch (Throwable t) { diagT('mount attempt threw', t); result.set('THREW') }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) } finally { watcher.stop() }

diag('RESULT: ' + result.get())
diag('=== probe-installed-profile-mount DONE ===')
