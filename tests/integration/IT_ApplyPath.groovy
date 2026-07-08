// IT_ApplyPath.groovy — the test that was MISSING (2026-07-08 regression guard).
//
// The plugin's ONLY model-write path is Compose -> Apply -> TransactionWrapper.executeWrite ->
// StereotypeManager.setSemanticConcepts. It was never exercised by an automated test, so:
//   (a) a real Apply-path code regression would go unnoticed, and
//   (b) the 2026-07-08 "NoClassDefFoundError: .../commands/TransactionWrapper" (a hot-overwritten
//       jar read through a live JVM's stale ZipFile offsets -> "invalid LOC header") surfaced only
//       at a user's first Apply click, ~10h after deploy, instead of in a test.
//
// This test closes both gaps:
//   1. CANARY: force-LOAD every plugin class through the plugin's OWN classloader. If the deployed
//      jar was hot-overwritten under this JVM, the stale-offset reads throw ZipException HERE.
//   2. REAL WRITE: drive the exact crashed chain — TransactionWrapper.executeWrite { setSemanticConcepts }
//      — inside a committed session on a fresh project, then read the compound back and assert it.
//
// Run via the REST harness (port 8765) against a freshly-started Cameo. No System.exit; closes the
// throwaway project without saving. Diagnostic log is portable (semantic.it.logdir).
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.Project
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.jar.JarFile

final String LOG_DIR = System.getProperty('semantic.it.logdir',
        new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final File LOG = new File(LOG_DIR, 'IT_ApplyPath.log'); LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

// Does a throwable (or any cause) look like the hot-overwrite corruption signature?
def isZipCorruption
isZipCorruption = { Throwable t -> if (t == null) return false
    if (t instanceof java.util.zip.ZipException) return true
    String m = (t.getMessage() ?: ''); if (m.contains('invalid LOC header') || m.contains('bad signature')) return true
    return isZipCorruption(t.getCause()) }

diag('=== IT_ApplyPath START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    // ---- locate the deployed plugin's OWN classloader (ownClassloader=true isolates its classes)
    ClassLoader pluginCL = null; Class pluginClass = null
    for (p in PluginUtils.getPlugins()) {
        if (p.getClass().getName().contains('SemanticAlignmentPlugin')) {
            pluginClass = p.getClass(); pluginCL = pluginClass.getClassLoader()
        }
    }
    check('plugin classloader located', pluginCL != null)
    if (pluginCL == null) { diag('RESULT: FAIL ' + fails); diag('=== IT_ApplyPath DONE ==='); return }

    // ---- (1) CANARY: force-load EVERY plugin class through pluginCL. A hot-overwritten jar read
    // through this JVM's stale ZipFile offsets throws ZipException here — the exact 2026-07-08 fault.
    def loc = pluginClass.getProtectionDomain().getCodeSource().getLocation()
    diag('plugin code source: ' + loc)
    int scanned = 0; def corrupt = []; def otherLinkFails = []
    try {
        def jarFile = new File(loc.toURI())
        if (jarFile.isFile() && jarFile.getName().endsWith('.jar')) {
            JarFile jf = new JarFile(jarFile)
            try {
                for (e in jf.entries()) {
                    String n = e.getName()
                    if (!n.endsWith('.class')) continue
                    if (!n.startsWith('com/nomagic/magicdraw/plugins/semantic/')) continue
                    String cn = n.substring(0, n.length() - 6).replace('/', '.')
                    scanned++
                    try {
                        Class.forName(cn, false, pluginCL)   // load+link (reads bytes), no <clinit>
                    } catch (Throwable t) {
                        if (isZipCorruption(t)) corrupt << (cn + ' :: ' + t.getClass().getSimpleName() + ': ' + t.getMessage())
                        else otherLinkFails << (cn + ' :: ' + t.getClass().getSimpleName() + ': ' + t.getMessage())
                    }
                }
            } finally { jf.close() }
        } else { diag('code source is not a jar (dev classes dir?) - skipping byte canary') }
    } catch (Throwable t) { diagT('canary scan threw', t); fails << 'canary-scan' }
    diag('canary: scanned=' + scanned + ' corrupt=' + corrupt.size() + ' otherLinkFails=' + otherLinkFails.size())
    if (!corrupt.isEmpty()) corrupt.each { diag('  CORRUPT ' + it) }
    if (!otherLinkFails.isEmpty()) otherLinkFails.take(8).each { diag('  (note) link-skip ' + it) }
    check('no hot-overwrite / ZipException corruption in any plugin class', corrupt.isEmpty())
    check('canary scanned a non-trivial number of plugin classes', scanned >= 20)

    // Explicitly RUN <clinit> on the class that crashed, to prove it fully initializes now.
    try { Class.forName('com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper', true, pluginCL)
        check('TransactionWrapper initializes (static init runs)', true)
    } catch (Throwable t) { diagT('TransactionWrapper init failed', t); fails << 'txwrapper-init' }

    // ---- reflection handles onto the DEPLOYED code paths
    def TX = Class.forName('com.nomagic.magicdraw.plugins.semantic.commands.TransactionWrapper', true, pluginCL)
    def SM = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, pluginCL)
    def mExecuteWrite   = TX.getMethod('executeWrite', Project, String, Runnable)
    def mEnsure         = SM.getMethod('ensureProfileAvailable', Project)
    def mIsInstrumented = SM.getMethod('isInstrumented', Project)
    def mDefaultIri     = SM.getMethod('defaultRootIri', Project)
    def mApplyModel     = SM.getMethod('applyModelInstrumentation', Project, String, String, String)
    def mSetConcepts    = SM.getMethod('setSemanticConcepts', Element, java.util.List)
    def mGetConcepts    = SM.getMethod('getMappedConcepts', Element)

    // ---- (2) REAL WRITE through the exact crashed chain on a fresh throwaway project
    SwingUtilities.invokeAndWait {
        def prj = null
        try {
            prj = pm.createProject()
            def model = prj.getPrimaryModel()
            def ef = prj.getElementsFactory()
            def sm = SessionManager.getInstance()

            sm.createSession(prj, 'IT_ApplyPath add element')
            def el = ef.createClassInstance(); el.setName('MosquitoKillingDrone'); el.setOwner(model)
            sm.closeSession(prj)

            // Mount the shipped profile (cold-mount can flake on the first attempt after a cold
            // start — retry a few times; see uaf-ontology-plugin-state COLD PROFILE-MOUNT FLAKE).
            boolean mounted = false
            for (int i = 0; i < 4 && !mounted; i++) {
                mounted = (boolean) mEnsure.invoke(null, prj)
                if (!mounted) { diag('  ensureProfileAvailable attempt ' + (i + 1) + ' -> false; retrying'); Thread.sleep(1500) }
            }
            check('profile mounted (deployed ensureProfileAvailable)', mounted)
            if (!mounted) { pm.closeProjectNoSave(prj); return }

            String rootIri = (String) mDefaultIri.invoke(null, prj)
            sm.createSession(prj, 'IT_ApplyPath instrument')
            mApplyModel.invoke(null, prj, rootIri, '1.0.0', 'semantic-alignment-plugin/IT_ApplyPath')
            sm.closeSession(prj)
            check('model instrumented', (boolean) mIsInstrumented.invoke(null, prj))

            // THE exact chain from the crash: TransactionWrapper.executeWrite { setSemanticConcepts }.
            String genusIri = rootIri + 'Drone'
            String diffClause = 'kills | http://purl.obolibrary.org/obo/IDOMAL_0000746 | Mosquito'
            java.util.List concepts = [genusIri, diffClause]
            Runnable writeOp = ({ mSetConcepts.invoke(null, el, concepts) } as Runnable)
            mExecuteWrite.invoke(null, prj, 'IT_ApplyPath apply compound', writeOp)
            diag('  executeWrite returned (no NoClassDefFoundError) — the crashed chain ran')

            // Read the compound back from the element's multi-valued tag.
            def got = (java.util.List) mGetConcepts.invoke(null, el)
            diag('  getMappedConcepts -> ' + got)
            check('compound stored: two clauses', got != null && got.size() == 2)
            check('genus stored at index 0', got != null && got.size() > 0 && String.valueOf(got.get(0)) == genusIri)
            check('differentia clause stored (kills + IDOMAL mosquito)', got != null && got.size() > 1
                    && String.valueOf(got.get(1)).contains('kills')
                    && String.valueOf(got.get(1)).contains('IDOMAL_0000746'))

            pm.closeProjectNoSave(prj)
        } catch (Throwable t) {
            diagT('apply-path body threw', t)
            fails << (isZipCorruption(t) ? 'APPLY-PATH-ZIP-CORRUPTION' : 'apply-path-exception')
            try { if (prj != null) pm.closeProjectNoSave(prj) } catch (Throwable ignored) {}
        }
    }
    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== IT_ApplyPath DONE ===')
