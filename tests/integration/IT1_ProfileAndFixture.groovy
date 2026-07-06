// IT1_ProfileAndFixture.groovy
// =====================================================================================
// Integration test 1: ensures the "UAF Semantic Alignment Profile" with the
// SemanticAlignment stereotype (mappedConceptURI : String) exists in the open project,
// then builds the GUI-test fixture package "SemanticAlignmentIT":
//   EchoBase (sumo:MilitaryBase)
//     +-- IonCannonControl (sumo:Device)          <- nested: exporter emits uaf:hasPart
//   TransportShip (sumo:Vehicle)
//   GuiMappingProbe (deliberately unmapped)       <- IT3 maps it through the sidebar GUI
//   Association "connected to": EchoBase -> TransportShip
// Idempotent: re-runs reuse existing elements. All writes in sessions on the EDT.
// Trace: PLG-REQ-03, PLG-REQ-06
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String PROFILE_NAME = 'UAF Semantic Alignment Profile'
final String STEREO_NAME = 'SemanticAlignment'
final String PROP_NAME = 'mappedConceptURI'
final String PKG_NAME = 'SemanticAlignmentIT'

final File LOG = new File(LOG_DIR, 'IT1_ProfileAndFixture.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== IT1 profile-and-fixture START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { fail('No open project - open a project in Cameo first.'); diag('RESULT: FAIL'); return }
diag('active project: ' + project.getName())

def sm = SessionManager.getInstance()

// Model writes happen on the EDT inside a session; the harness runs scripts on a worker
// thread, so every mutation below is funneled through invokeAndWait.
def onEdtInSession = { String sessionName, Closure work ->
    def error = null
    SwingUtilities.invokeAndWait {
        sm.createSession(project, sessionName)
        try {
            work.call()
            sm.closeSession(project)
        } catch (Throwable t) {
            try { sm.cancelSession(project) } catch (Throwable ignored) {}
            error = t
        }
    }
    if (error != null) { throw error }
}

try {
    // --- 1) SHIPPED profile module auto-mount (owner decision: a real profile file
    // deployed with the plugin, generic across UML-based models - never hand-created) --
    def plugin = com.nomagic.magicdraw.plugins.PluginUtils.getPlugins().find {
        it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
    }
    if (plugin == null) { fail('plugin not loaded'); diag('RESULT: FAIL'); return }
    def mgrCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager',
            true, plugin.getClass().getClassLoader())
    def projCls = Class.forName('com.nomagic.magicdraw.core.Project', true, plugin.getClass().getClassLoader())
    def mountedHolder = [:]
    SwingUtilities.invokeAndWait {
        mountedHolder.ok = mgrCls.getMethod('ensureProfileAvailable', projCls).invoke(null, project)
    }
    if (!mountedHolder.ok) {
        fail('shipped Semantic Alignment Profile module did not mount'); diag('RESULT: FAIL'); return
    }
    def stereo = StereotypesHelper.getStereotype(project, STEREO_NAME)
    if (stereo == null) {
        fail('stereotype unresolvable after module mount'); diag('RESULT: FAIL'); return
    }
    diag('shipped profile mounted; stereotype: ' + stereo.getQualifiedName())

    // --- 2) Fixture package ---------------------------------------------------------
    def model = project.getPrimaryModel()
    def stereoRef = stereo
    def created = [:]
    onEdtInSession('IT1 build fixture') {
        def ef = project.getElementsFactory()
        // New workflow (2026-07-06): alignment requires an INSTRUMENTED model. Apply the
        // model-level SemanticModel stereotype (root IRI + version) so the sidebar GUI
        // mapping path in IT3 sees the model already instrumented and skips the confirm
        // prompt. Mirrors StereotypeManager.applyModelInstrumentation.
        def modelStereo = StereotypesHelper.getStereotype(project, 'SemanticModel')
        if (modelStereo != null && !StereotypesHelper.hasStereotype(model, modelStereo)) {
            StereotypesHelper.addStereotype(model, modelStereo)
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'ontologyRootIRI',
                    'http://semantic.alignment/model/' + project.getName() + '#')
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'ontologyVersion', '1.0.0')
        }
        def pkg = model.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
        if (pkg == null) {
            pkg = ef.createPackageInstance()
            pkg.setName(PKG_NAME)
            pkg.setOwner(model)
        }
        def ensureClass = { String name, owner ->
            def found = owner.getOwnedElement().find { it.respondsTo('getName') && it.getName() == name }
            if (found == null) {
                found = ef.createClassInstance()
                found.setName(name)
                found.setOwner(owner)
            }
            found
        }
        def echoBase = ensureClass('EchoBase', pkg)
        def ionCannon = ensureClass('IonCannonControl', echoBase) // nested -> uaf:hasPart
        def transport = ensureClass('TransportShip', pkg)
        def guiProbe = ensureClass('GuiMappingProbe', pkg)        // stays unmapped for IT3

        def align = { el, String uri ->
            if (!StereotypesHelper.hasStereotype(el, stereoRef)) {
                StereotypesHelper.addStereotype(el, stereoRef)
            }
            StereotypesHelper.setStereotypePropertyValue(el, stereoRef, PROP_NAME, uri)
        }
        align(echoBase, 'sumo:MilitaryBase')
        align(ionCannon, 'sumo:Device')
        align(transport, 'sumo:Vehicle')

        def assocName = 'connected to'
        def haveAssoc = pkg.getOwnedElement().any {
            it.getClass().getName().contains('Association') && it.respondsTo('getName') && it.getName() == assocName
        }
        if (!haveAssoc) {
            def assoc = ef.createAssociationInstance()
            assoc.setName(assocName)
            assoc.setOwner(pkg)
            def ends = assoc.getMemberEnd()
            if (ends != null && ends.size() >= 2) {
                // memberEnd[0] is the source end, memberEnd[1] the target end - the
                // exporter emits <end0.type> uaf:connectedTo <end1.type>
                ends.get(0).setType(echoBase)
                ends.get(1).setType(transport)
            } else {
                throw new IllegalStateException('association factory created ' + (ends == null ? 0 : ends.size()) + ' member ends')
            }
        }
        created.echoBase = echoBase
        created.ionCannon = ionCannon
        created.transport = transport
        created.guiProbe = guiProbe
    }

    // --- 3) Assertions ---------------------------------------------------------------
    ['echoBase': 'sumo:MilitaryBase', 'ionCannon': 'sumo:Device', 'transport': 'sumo:Vehicle'].each { key, uri ->
        def el = created[key]
        if (!StereotypesHelper.hasStereotype(el, stereo)) {
            fail(key + ' is missing the SemanticAlignment stereotype')
        } else {
            def values = StereotypesHelper.getStereotypePropertyValue(el, stereo, PROP_NAME)
            def actual = (values == null || values.isEmpty()) ? null : values.get(0)?.toString()
            if (actual == uri) {
                diag(key + ' aligned OK -> ' + actual)
            } else {
                fail(key + ' tagged value mismatch: expected ' + uri + ' got ' + actual)
            }
        }
    }
    if (StereotypesHelper.hasStereotype(created.guiProbe, stereo)) {
        fail('GuiMappingProbe must start unmapped (IT3 maps it through the GUI)')
    } else {
        diag('GuiMappingProbe unmapped as required')
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT1', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT1 DONE ===')
