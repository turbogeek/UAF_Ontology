// ProbeMountSar.groovy — diagnose WHY useModule fails to mount the Semantic Alignment
// Profile into the open SAR sample. Reproduces the mount on the EDT with a dialog-capturing
// watcher and logs the useModule return, any exception, the modal dialog text, and the
// project's used modules. Read-only (does not save).
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.Window
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeMountSar.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

def scrapeText
scrapeText = { java.awt.Container c ->
    def sb = new StringBuilder()
    for (comp in c.getComponents()) {
        if (comp instanceof javax.swing.JLabel && comp.getText()) { sb.append(comp.getText()).append(' | ') }
        else if (comp instanceof javax.swing.text.JTextComponent && comp.getText()) { sb.append(comp.getText()).append(' | ') }
        else if (comp instanceof java.awt.Container) { sb.append(scrapeText(comp)) }
    }
    return sb.toString()
}

diag('=== probe-mount-sar START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    SwingUtilities.invokeAndWait {
        try {
            def prj = app.getProject()
            diag('project: ' + (prj == null ? 'null' : prj.getName()))
            if (prj == null) { return }

            def file = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Profile.mdzip')
            diag('profile file: ' + file + ' exists=' + file.exists() + ' size=' + (file.exists() ? file.length() : 0))
            diag('profiles dir: ' + ApplicationEnvironment.getProfilesDirectory())

            def before = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            diag('stereotype BEFORE mount: ' + before)

            // Watcher: log then dismiss any dialog that pops during useModule.
            def baseline = new HashSet(Arrays.asList(Window.getWindows()))
            def captured = new StringBuilder()
            def watcher = new Timer(120, null)
            watcher.addActionListener({ e ->
                for (w in Window.getWindows()) {
                    if (w instanceof java.awt.Dialog && w.isVisible() && !baseline.contains(w)) {
                        String txt = 'title=[' + w.getTitle() + '] text=[' + scrapeText(w).trim() + ']'
                        captured.append(txt).append('\n')
                        diag('  DIALOG: ' + txt)
                        try { w.setVisible(false); w.dispose() } catch (Throwable ignored) {}
                    }
                }
            } as java.awt.event.ActionListener)
            watcher.start()

            def used = null
            try {
                def desc = ProjectDescriptorsFactory.createProjectDescriptor(file.toURI())
                diag('descriptor: ' + desc)
                used = pm.useModule(prj, desc)
                diag('useModule -> ' + used)
            } catch (Throwable t) {
                diagT('useModule threw', t)
            } finally {
                watcher.stop()
            }

            def after = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            diag('stereotype AFTER mount: ' + after)
            diag('captured dialogs: ' + (captured.length() == 0 ? '(none)' : ('\n' + captured.toString())))

            // List the project's used modules (best-effort via introspection).
            try {
                def primary = prj.getPrimaryProject()
                if (primary.respondsTo('getProjectUsages')) {
                    def usages = primary.getProjectUsages()
                    diag('projectUsages count: ' + (usages == null ? 'null' : usages.size()))
                    usages?.each { u ->
                        def uri = u.respondsTo('getUsedProjectURI') ? u.getUsedProjectURI() : u
                        diag('  used: ' + uri)
                    }
                } else {
                    diag('primary project has no getProjectUsages; methods: '
                            + primary.getClass().getMethods().findAll { it.getName().toLowerCase().contains('usage') || it.getName().toLowerCase().contains('module') }.collect { it.getName() }.unique())
                }
            } catch (Throwable t) { diagT('usage listing failed', t) }
        } catch (Throwable t) {
            diagT('probe threw', t)
        }
    }
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-mount-sar DONE ===')
