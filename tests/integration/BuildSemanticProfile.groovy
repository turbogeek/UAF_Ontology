// BuildSemanticProfile.groovy — one-time artifact builder (not a test)
// =====================================================================================
// Owner decision: the Semantic Alignment profile must be a REAL, shipped profile module
// (not created programmatically inside user projects), and GENERIC - usable in UML,
// SysML 1.x, UAF, or any UML-based model. This script builds it once as its own
// project, shares the profile package, and saves it to the repo:
//   plugin/profiles/Semantic Alignment Profile.mdzip
// Stereotype: SemanticAlignment (extends the UML Element metaclass = applies anywhere)
// Tags: mappedConceptURI:String, ontologySource:String, mappingConfidence:String
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File OUT = new File('E:\\_Documents\\git\\UAF_Ontology\\plugin\\profiles', 'Semantic Alignment Profile.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'BuildSemanticProfile.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}

diag('=== build-semantic-profile START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
def previous = pm.getActiveProject()

// exportModule raises a modal "Save" JFileChooser to pick the destination; auto-drive
// it (set the file, approve) so the build is fully non-interactive. Runs as a Timer on
// the EDT, which keeps firing because a modal dialog pumps the event queue.
def chooserDriver = new javax.swing.Timer(200, null)
chooserDriver.addActionListener({ e ->
    java.awt.Window.getWindows().findAll { it.isVisible() && it instanceof java.awt.Dialog }.each { dlg ->
        def chooser = null
        def find
        find = { java.awt.Component c ->
            if (c instanceof javax.swing.JFileChooser) { chooser = c }
            if (chooser == null && c instanceof java.awt.Container) { c.getComponents().each { find(it) } }
        }
        find(dlg)
        if (chooser != null) {
            try {
                chooser.setSelectedFile(OUT)
                chooser.approveSelection()
                diag('auto-approved Save chooser -> ' + OUT)
            } catch (Throwable t) { diag('chooser drive failed: ' + t) }
        }
    }
} as java.awt.event.ActionListener)
chooserDriver.start()

try {
    OUT.getParentFile().mkdirs()
    def result = [ok: false]
    def error = null
    SwingUtilities.invokeAndWait {
        try {
            def project = pm.createProject()
            diag('fresh project created: ' + project.getName())
            def sm = SessionManager.getInstance()
            sm.createSession(project, 'Build Semantic Alignment Profile')
            try {
                def ef = project.getElementsFactory()
                def profile = ef.createProfileInstance()
                profile.setName('Semantic Alignment Profile')
                profile.setOwner(project.getPrimaryModel())

                def metaElement = StereotypesHelper.getAllMetaClasses(project).find { it.getName() == 'Element' }
                if (metaElement == null) { throw new IllegalStateException('UML metaclass Element not found') }
                def stereo = StereotypesHelper.createStereotype(project, 'SemanticAlignment', [metaElement])
                stereo.setOwner(profile)

                def strType = null
                try {
                    def byQn = Class.forName('com.nomagic.magicdraw.uml.Finder')
                            .getMethod('byQualifiedName').invoke(null)
                    strType = byQn.find(project, 'UML Standard Profile::UML2 Metamodel::PrimitiveTypes::String')
                } catch (Throwable t) { diag('note: String primitive lookup failed (' + t + ')') }

                ['mappedConceptURI', 'ontologySource', 'mappingConfidence'].each { tagName ->
                    def prop = ef.createPropertyInstance()
                    prop.setName(tagName)
                    if (strType != null) { prop.setType(strType) }
                    stereo.getOwnedAttribute().add(prop)
                }
                sm.closeSession(project)
            } catch (Throwable t) {
                try { sm.cancelSession(project) } catch (Throwable ignored) {}
                throw t
            }

            // Export the profile package AS A SHARED MODULE .mdzip so any project can
            // useModule() it. exportModule is the canonical "make a reusable module"
            // API; a plainly-saved project cannot be mounted (that was the mount error).
            def profilePkg = project.getPrimaryModel().getOwnedElement().find {
                it.respondsTo('getName') && it.getName() == 'Semantic Alignment Profile'
            }
            if (profilePkg == null) { throw new IllegalStateException('profile package not found after creation') }
            if (OUT.exists()) { OUT.delete() }
            def moduleDescriptor = ProjectDescriptorsFactory.createProjectDescriptor(OUT.toURI())
            pm.exportModule(project, [profilePkg], 'Semantic Alignment Profile', moduleDescriptor)
            diag('exportModule -> ' + OUT)
            pm.closeProjectNoSave()
            result.ok = OUT.exists() && OUT.length() > 0
        } catch (Throwable t) { error = t }
    }
    if (error != null) { throw error }
    if (result.ok && OUT.exists()) {
        diag('RESULT: PASS (' + OUT.length() + ' bytes)')
    } else {
        diag('RESULT: FAIL')
    }
} catch (Throwable t) {
    diagT('UNCAUGHT', t)
    diag('RESULT: FAIL')
} finally {
    try { chooserDriver.stop() } catch (Throwable ignored) {}
    // Reopen the host project for subsequent tests if we displaced it
    try {
        if (previous != null && Application.getInstance().getProject() == null) {
            diag('note: previous project was displaced; reopen manually or rerun launcher')
        }
    } catch (Throwable ignored) {}
}
diag('=== build-semantic-profile DONE ===')
