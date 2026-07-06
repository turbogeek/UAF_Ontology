// ProbeDeployedInstrument.groovy — GUI-integration proof that the DEPLOYED plugin
// (ownClassloader) exposes working instrument / un-instrument logic and that the new menu
// class is loadable. Loads the plugin's classes via its own classloader (per the
// PluginUtils.getPlugins pattern) and drives StereotypeManager through reflection on a
// fresh project: mount profile -> instrument -> align -> assert -> un-instrument -> assert.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.Project
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeDeployedInstrument.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

diag('=== probe-deployed-instrument START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    // Locate the deployed plugin's classloader (ownClassloader=true isolates its classes).
    ClassLoader pluginCL = null
    for (p in PluginUtils.getPlugins()) {
        if (p.getClass().getName().contains('SemanticAlignmentPlugin')) {
            pluginCL = p.getClass().getClassLoader()
        }
    }
    check('plugin classloader located', pluginCL != null)
    if (pluginCL == null) { diag('RESULT: FAIL ' + fails); return }

    def SM = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, pluginCL)
    def MENU = Class.forName('com.nomagic.magicdraw.plugins.semantic.SemanticMenuConfigurator', true, pluginCL)
    check('StereotypeManager class deployed', SM != null)
    check('SemanticMenuConfigurator class deployed', MENU != null)

    // Reflection handles on the deployed StereotypeManager methods.
    def mIsInstrumented = SM.getMethod('isInstrumented', Project)
    def mDefaultIri     = SM.getMethod('defaultRootIri', Project)
    def mEnsure         = SM.getMethod('ensureProfileAvailable', Project)
    def mApplyModel     = SM.getMethod('applyModelInstrumentation', Project, String, String, String)
    def mApplyMapping   = SM.getMethod('applySemanticMapping', Element, String)
    def mAlignedCount   = SM.getMethod('alignedElementCount', Project)
    def mRemoveAll      = SM.getMethod('removeAllInstrumentation', Project)

    SwingUtilities.invokeAndWait {
        def prj = null
        try {
            prj = pm.createProject()
            def model = prj.getPrimaryModel()
            def ef = prj.getElementsFactory()
            def sm = SessionManager.getInstance()

            sm.createSession(prj, 'add test element')
            def testEl = ef.createClassInstance(); testEl.setName('OpticalSensor'); testEl.setOwner(model)
            sm.closeSession(prj)

            check('not instrumented initially', !(mIsInstrumented.invoke(null, prj)))

            // Mount the shipped profile via DEPLOYED code (outside a session).
            check('ensureProfileAvailable (deployed)', (boolean) mEnsure.invoke(null, prj))

            String rootIri = (String) mDefaultIri.invoke(null, prj)
            diag('  defaultRootIri -> ' + rootIri)

            sm.createSession(prj, 'instrument')
            mApplyModel.invoke(null, prj, rootIri, '1.0.0', 'semantic-alignment-plugin/test')
            mApplyMapping.invoke(null, testEl, 'http://purl.obolibrary.org/obo/NCIT_C54117')
            sm.closeSession(prj)

            check('isInstrumented after instrument', (boolean) mIsInstrumented.invoke(null, prj))
            check('one aligned element', ((int) mAlignedCount.invoke(null, prj)) == 1)

            sm.createSession(prj, 'un-instrument')
            int removed = (int) mRemoveAll.invoke(null, prj)
            sm.closeSession(prj)
            diag('  removeAllInstrumentation -> ' + removed)
            check('removed 2 applications (model + element)', removed == 2)
            check('not instrumented after removal', !(mIsInstrumented.invoke(null, prj)))
            check('zero aligned after removal', ((int) mAlignedCount.invoke(null, prj)) == 0)

            pm.closeProjectNoSave(prj)
        } catch (Throwable t) {
            diagT('probe body threw', t); fails << 'exception'
            try { if (prj != null) pm.closeProjectNoSave(prj) } catch (Throwable ignored) {}
        }
    }
    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== probe-deployed-instrument DONE ===')
