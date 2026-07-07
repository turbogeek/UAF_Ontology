// VerifyCompoundConcept.groovy
// =====================================================================================
// Live proof of COMPOUND concepts (genus + differentia) through the DEPLOYED plugin classes:
//   1. compose button is present in the sidebar (UI wired)
//   2. store encoded clauses (genus, "suppresses | IRI", "uses | IRI") via StereotypeManager
//      -> read back round-trips
//   3. CompoundConcept + SBVREngine render "A Mosquito Suppression Drone is a Drone that
//      suppresses a Mosquito and uses a Chemical Sprayer."
//   4. SemanticRDFExporter emits the relation triple + skos:definition in Turtle
// Trace: design/compound_concepts.md
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element
import com.nomagic.magicdraw.core.Project

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final String PKG_NAME = 'CompoundIT'
final String GENUS = 'http://example.org/pest#Drone'
final String MOSQ  = 'http://example.org/pest#Mosquito'
final String SPRAY = 'http://example.org/pest#ChemicalSprayer'

final File LOG = new File(LOG_DIR, 'VerifyCompoundConcept.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== VerifyCompoundConcept START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not in CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) { fail('No open project - run OpenScratchProject first.'); diag('RESULT: FAIL'); return }

try {
    // --- deployed plugin classloader ---
    def pluginCL = null
    for (p in PluginUtils.getPlugins()) {
        if (p.getClass().getName().contains('SemanticAlignmentPlugin')) { pluginCL = p.getClass().getClassLoader(); break }
    }
    if (pluginCL == null) { fail('plugin classloader not found'); diag('RESULT: FAIL'); return }
    def SM  = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, pluginCL)
    def CC  = Class.forName('com.nomagic.magicdraw.plugins.semantic.align.CompoundConcept', true, pluginCL)
    def SBV = Class.forName('com.nomagic.magicdraw.plugins.semantic.SBVREngine', true, pluginCL)
    def EXP = Class.forName('com.nomagic.magicdraw.plugins.semantic.SemanticRDFExporter', true, pluginCL)
    def mIsInstrumented = SM.getMethod('isInstrumented', Project)
    def mEnsure         = SM.getMethod('ensureProfileAvailable', Project)
    def mDefaultIri     = SM.getMethod('defaultRootIri', Project)
    def mApplyModel     = SM.getMethod('applyModelInstrumentation', Project, String, String, String)
    def mSetConcepts    = SM.getMethod('setSemanticConcepts', Element, List)
    def mGetConcepts    = SM.getMethod('getMappedConcepts', Element)

    // --- compose button present? ---
    def findByName = { String nm ->
        def ref = new AtomicReference(); def walk
        walk = { java.awt.Component c -> if (ref.get() != null) return; if (nm == c.getName()) { ref.set(c); return }; if (c instanceof java.awt.Container) c.getComponents().each { walk(it) } }
        SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (ref.get() == null) walk(w) } }
        return ref.get()
    }
    def composeBtn = findByName('semantic.composeButton')
    if (composeBtn == null) fail('compose button (semantic.composeButton) not in the sidebar') else diag('OK compose button present: "' + composeBtn.getText() + '"')

    // --- instrument + create element + store compound clauses ---
    def clauses = [GENUS, 'suppresses' + ' | ' + MOSQ, 'uses' + ' | ' + SPRAY]
    def holder = [:]; def error = null
    SwingUtilities.invokeAndWait {
        def sm = SessionManager.getInstance()
        sm.createSession(project, 'Compound IT')
        try {
            if (!(boolean) mIsInstrumented.invoke(null, project)) {
                sm.closeSession(project)                 // instrument needs its own tx boundary
                mEnsure.invoke(null, project)
                String iri = (String) mDefaultIri.invoke(null, project)
                sm.createSession(project, 'Instrument')
                mApplyModel.invoke(null, project, iri, '1.0.0', 'semantic-alignment-plugin/test')
            }
            def ef = project.getElementsFactory(); def root = project.getPrimaryModel()
            def pkg = root.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
            if (pkg == null) { pkg = ef.createPackageInstance(); pkg.setName(PKG_NAME); pkg.setOwner(root) }
            def el = ef.createClassInstance(); el.setName('MosquitoSuppressionDrone'); el.setOwner(pkg)
            mSetConcepts.invoke(null, el, clauses)
            holder.el = el
            sm.closeSession(project)
        } catch (Throwable t) { try { sm.cancelSession(project) } catch (Throwable ig) {}; error = t }
    }
    if (error != null) throw error
    def el = holder.el
    diag('created + stored compound on ' + el.getName())

    // --- round-trip ---
    def stored = mGetConcepts.invoke(null, el) as List
    diag('read back: ' + stored)
    if (stored != clauses) fail('stored clauses did not round-trip: ' + stored) else diag('OK clauses round-trip')

    // --- SBVR reads correctly ---
    def sbvrEngine = SBV.getDeclaredConstructor().newInstance()
    def compound = CC.getMethod('parse', String, List).invoke(null, 'MosquitoSuppressionDrone', stored)
    String sbvr = (String) CC.getMethod('toSbvr', SBV).invoke(compound, sbvrEngine)
    diag('SBVR: ' + sbvr)
    if (sbvr.contains('is a Drone') && sbvr.contains('that suppresses a Mosquito') && sbvr.contains('and uses a Chemical Sprayer')) diag('OK SBVR reads correctly')
    else fail('SBVR did not read correctly: ' + sbvr)

    // --- RDF export has the relation + definition ---
    def exporter = EXP.getDeclaredConstructor(Project).newInstance(project)
    String ttl = (String) EXP.getMethod('exportToTurtleString').invoke(exporter)
    boolean hasRel = ttl.contains('suppresses') && ttl.contains('Mosquito')
    boolean hasDef = ttl.toLowerCase().contains('definition')
    diag('export: suppresses+Mosquito=' + hasRel + '  skos:definition=' + hasDef)
    if (!hasRel) fail('exported Turtle missing the suppresses/Mosquito relation')
    if (!hasDef) fail('exported Turtle missing skos:definition')
} catch (Throwable t) {
    diagT('UNCAUGHT', t); pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== VerifyCompoundConcept DONE ===')
