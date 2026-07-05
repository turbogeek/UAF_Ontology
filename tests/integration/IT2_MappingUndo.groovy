// IT2_MappingUndo.groovy
// =====================================================================================
// Integration test 2: applies a semantic mapping to a fresh probe element inside one
// session, then verifies Cameo's command history can undo and redo it (design spec 10.2
// testUndoRedoSemanticMapping). Uses the plugin's own StereotypeManager through the
// plugin classloader so the production write path is what gets exercised.
// Requires IT1 to have run (profile + fixture package present).
// Trace: PLG-REQ-03, PLG-REQ-06
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String STEREO_NAME = 'SemanticAlignment'
final String PROP_NAME = 'mappedConceptURI'
final String PKG_NAME = 'SemanticAlignmentIT'
final String CONCEPT = 'bmm:Goal'

final File LOG = new File(LOG_DIR, 'IT2_MappingUndo.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== IT2 mapping-undo START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { fail('No open project.'); diag('RESULT: FAIL'); return }

def stereo = StereotypesHelper.getStereotype(project, STEREO_NAME)
if (stereo == null) { fail('SemanticAlignment stereotype missing - run IT1 first.'); diag('RESULT: FAIL'); return }
def pkg = project.getPrimaryModel().getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
if (pkg == null) { fail('Fixture package missing - run IT1 first.'); diag('RESULT: FAIL'); return }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { fail('Semantic Alignment plugin not loaded.'); diag('RESULT: FAIL'); return }
def pluginCL = plugin.getClass().getClassLoader()

def sm = SessionManager.getInstance()
def onEdt = { Closure work ->
    def error = null
    SwingUtilities.invokeAndWait {
        try { work.call() } catch (Throwable t) { error = t }
    }
    if (error != null) { throw error }
}

try {
    // Fresh probe each run so the undo history starts clean for this scenario.
    String probeName = 'UndoProbe_' + System.currentTimeMillis()
    def probeHolder = [:]
    onEdt {
        sm.createSession(project, 'IT2 create probe')
        try {
            def probe = project.getElementsFactory().createClassInstance()
            probe.setName(probeName)
            probe.setOwner(pkg)
            probeHolder.el = probe
            sm.closeSession(project)
        } catch (Throwable t) {
            try { sm.cancelSession(project) } catch (Throwable ignored) {}
            throw t
        }
    }
    def probe = probeHolder.el
    diag('created probe: ' + probeName)

    // Production write path: plugin's TransactionWrapper + StereotypeManager.
    def txCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper', true, pluginCL)
    def mgrCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, pluginCL)
    def elementCls = Class.forName('com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element', true, pluginCL)
    def applyMethod = mgrCls.getMethod('applySemanticMapping', elementCls, String)
    onEdt {
        def projCls = Class.forName('com.nomagic.magicdraw.core.Project', true, pluginCL)
        txCls.getMethod('executeWrite', projCls, String, Runnable)
                .invoke(null, project, 'IT2 Apply Semantic Mapping',
                        { applyMethod.invoke(null, probe, CONCEPT) } as Runnable)
    }
    if (!StereotypesHelper.hasStereotype(probe, stereo)) {
        fail('mapping did not apply the stereotype')
    } else {
        def values = StereotypesHelper.getStereotypePropertyValue(probe, stereo, PROP_NAME)
        def actual = (values == null || values.isEmpty()) ? null : values.get(0)?.toString()
        if (actual == CONCEPT) { diag('mapping applied OK -> ' + actual) }
        else { fail('tagged value mismatch after mapping: ' + actual) }
    }

    // Undo / redo through Cameo's command history (the plugin writes via sessions, so
    // its transactions must land on the undo stack).
    def history = project.respondsTo('getCommandHistory') ? project.getCommandHistory() : null
    if (history == null || !history.respondsTo('undo') || !history.respondsTo('redo')) {
        diag('WARN: CommandHistory undo/redo not available on this API version - phase skipped')
    } else {
        onEdt { history.undo() }
        if (StereotypesHelper.hasStereotype(probe, stereo)) {
            fail('undo did not remove the semantic mapping')
        } else {
            diag('undo OK: stereotype removed')
        }
        onEdt { history.redo() }
        if (!StereotypesHelper.hasStereotype(probe, stereo)) {
            fail('redo did not re-apply the semantic mapping')
        } else {
            def values = StereotypesHelper.getStereotypePropertyValue(probe, stereo, PROP_NAME)
            def actual = (values == null || values.isEmpty()) ? null : values.get(0)?.toString()
            if (actual == CONCEPT) { diag('redo OK: mapping restored -> ' + actual) }
            else { fail('redo restored stereotype but tagged value is ' + actual) }
        }
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT2', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT2 DONE ===')
