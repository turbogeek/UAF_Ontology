// CreateMosquitoKillingDrone.groovy
// =====================================================================================
// Creates the owner's example COMPOUND concept in the open model:
//   MosquitoKillingDrone  =  a kind of Drone  that  kills  a Mosquito
// Genus "Drone" is a model/overlay concept (a drone is a kind of aircraft); the qualifier
// "Mosquito" is grounded in the REAL malaria ontology concept IDOMAL:0000746 (found online),
// with a display label so the SBVR reads its name. Reports the SBVR sentence + the element's
// RDF triples. Trace: design/compound_concepts.md
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.Project
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final String PKG_NAME = 'PestDroneIT'
final String NAME  = 'MosquitoKillingDrone'
final String DRONE = 'http://purl.org/uaf/ontology#Drone'                       // genus (overlay: Drone is-a Aircraft)
final String MOSQ  = 'http://purl.obolibrary.org/obo/IDOMAL_0000746'            // real malaria-ontology Mosquito
final String CLAUSE = 'kills' + ' | ' + MOSQ + ' | ' + 'Mosquito'              // relation | IRI | label

final File LOG = new File(LOG_DIR, 'CreateMosquitoKillingDrone.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== CreateMosquitoKillingDrone START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not in CATIA Magic.'); diag('RESULT: SKIP'); return }
def project = app.getProject()
if (project == null) {
    // Open a blank project ourselves (in THIS run, so no inter-run /stop can interrupt it).
    // Fire NON-blocking (createProject on the EDT can block an invokeAndWait) and poll.
    SwingUtilities.invokeLater { try { app.getProjectsManager().createProject() } catch (Throwable ignored) {} }
    for (int i = 0; i < 50 && project == null; i++) { Thread.sleep(200); project = app.getProject() }
    diag('opened scratch project: ' + (project == null ? 'FAILED' : project.getName()))
    Thread.sleep(3000) // let the template finish loading before we mount the profile / instrument
}
if (project == null) { fail('Could not open a project.'); diag('RESULT: FAIL'); return }

try {
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
    def mEnsure = SM.getMethod('ensureProfileAvailable', Project)
    def mDefaultIri = SM.getMethod('defaultRootIri', Project)
    def mApplyModel = SM.getMethod('applyModelInstrumentation', Project, String, String, String)
    def mSetConcepts = SM.getMethod('setSemanticConcepts', Element, List)
    def mGetConcepts = SM.getMethod('getMappedConcepts', Element)

    def clauses = [DRONE, CLAUSE]
    def holder = [:]; def error = null
    SwingUtilities.invokeAndWait {
        def sm = SessionManager.getInstance()
        sm.createSession(project, 'Create MosquitoKillingDrone')
        try {
            if (!(boolean) mIsInstrumented.invoke(null, project)) {
                sm.closeSession(project)
                boolean mounted = (boolean) mEnsure.invoke(null, project)
                diag('ensureProfileAvailable -> ' + mounted)
                String iri = (String) mDefaultIri.invoke(null, project)
                sm.createSession(project, 'Instrument')
                mApplyModel.invoke(null, project, iri, '1.0.0', 'semantic-alignment-plugin/pest-demo')
            }
            def ef = project.getElementsFactory(); def root = project.getPrimaryModel()
            def pkg = root.getOwnedElement().find { it.respondsTo('getName') && it.getName() == PKG_NAME }
            if (pkg == null) { pkg = ef.createPackageInstance(); pkg.setName(PKG_NAME); pkg.setOwner(root) }
            def el = pkg.getOwnedElement().find { it.respondsTo('getName') && it.getName() == NAME }
            if (el == null) { el = ef.createClassInstance(); el.setName(NAME); el.setOwner(pkg) }
            mSetConcepts.invoke(null, el, clauses)
            holder.el = el
            sm.closeSession(project)
        } catch (Throwable t) { try { sm.cancelSession(project) } catch (Throwable ig) {}; error = t }
    }
    if (error != null) throw error
    def el = holder.el
    diag('created ' + NAME + ' in package ' + PKG_NAME)

    def stored = mGetConcepts.invoke(null, el) as List
    diag('stored concepts: ' + stored)

    def sbvrEngine = SBV.getDeclaredConstructor().newInstance()
    def compound = CC.getMethod('parse', String, List).invoke(null, NAME, stored)
    String sbvr = (String) CC.getMethod('toSbvr', SBV).invoke(compound, sbvrEngine)
    diag('SBVR: ' + sbvr)

    String want = 'is a Drone that kills a Mosquito'
    if (!sbvr.contains(want)) fail('SBVR did not read "' + want + '": ' + sbvr)

    def exporter = EXP.getDeclaredConstructor(Project).newInstance(project)
    String ttl = (String) EXP.getMethod('exportToTurtleString').invoke(exporter)
    diag('--- element RDF (Turtle lines mentioning this element / Drone / Mosquito / definition) ---')
    ttl.readLines().findAll { it =~ /(?i)drone|mosquito|IDOMAL|definition|kills/ }.take(14).each { diag('   ' + it.trim()) }
    if (!(ttl.contains('IDOMAL_0000746'))) fail('exported RDF missing the real IDOMAL mosquito IRI')
} catch (Throwable t) {
    diagT('UNCAUGHT', t); pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== CreateMosquitoKillingDrone DONE ===')
