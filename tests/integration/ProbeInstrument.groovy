// ProbeInstrument.groovy — prove the instrument / un-instrument logic against the REAL
// MagicDraw API before baking it into the Java plugin (scientific method: verify first).
// Steps: create project -> add a test element -> mount the rebuilt Semantic Alignment
// Profile -> assert BOTH stereotypes (SemanticAlignment + SemanticModel) resolve ->
// INSTRUMENT (apply SemanticModel to the model root with root IRI + version + provenance;
// apply SemanticAlignment to the element) -> assert counts + tag reads -> UN-INSTRUMENT
// (remove all applications + the model stereotype) -> assert clean -> close no-save.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.ApplicationEnvironment
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeInstrument.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

diag('=== probe-instrument START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    SwingUtilities.invokeAndWait {
        def prj = null
        try {
            prj = pm.createProject()
            if (prj == null) { diag('createProject null'); fails << 'createProject'; return }
            diag('project: ' + prj.getName())
            def sm = SessionManager.getInstance()
            def ef = prj.getElementsFactory()
            def model = prj.getPrimaryModel()

            // A test element to align.
            sm.createSession(prj, 'add test element')
            def testEl = ef.createClassInstance(); testEl.setName('OpticalSensor'); testEl.setOwner(model)
            sm.closeSession(prj)

            // Mount the shipped profile module (OUTSIDE a session).
            def file = new File(ApplicationEnvironment.getProfilesDirectory(), 'Semantic Alignment Profile.mdzip')
            diag('mount from: ' + file + ' exists=' + file.exists())
            def desc = ProjectDescriptorsFactory.createProjectDescriptor(file.toURI())
            def used = pm.useModule(prj, desc)
            diag('useModule -> ' + used)

            def alignStereo = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            def modelStereo = StereotypesHelper.getStereotype(prj, 'SemanticModel')
            check('SemanticAlignment stereotype resolves', alignStereo != null)
            check('SemanticModel stereotype resolves', modelStereo != null)
            if (alignStereo == null || modelStereo == null) { pm.closeProjectNoSave(prj); return }

            // ---- INSTRUMENT ----
            String rootIri = 'http://semantic.alignment/model/' + prj.getName() + '#'
            String version = '1.0.0'
            String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date())
            sm.createSession(prj, 'instrument model')
            StereotypesHelper.addStereotype(model, modelStereo)
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'ontologyRootIRI', rootIri)
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'ontologyVersion', version)
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'instrumentedBy', 'semantic-alignment-plugin/2.2.0')
            StereotypesHelper.setStereotypePropertyValue(model, modelStereo, 'instrumentedDate', stamp)
            StereotypesHelper.addStereotype(testEl, alignStereo)
            StereotypesHelper.setStereotypePropertyValue(testEl, alignStereo, 'mappedConceptURI', 'http://purl.obolibrary.org/obo/NCIT_C54117')
            sm.closeSession(prj)

            check('model root has SemanticModel', StereotypesHelper.hasStereotype(model, modelStereo))
            check('element has SemanticAlignment', StereotypesHelper.hasStereotype(testEl, alignStereo))
            def gotIri = StereotypesHelper.getStereotypePropertyValue(model, modelStereo, 'ontologyRootIRI')
            def gotVer = StereotypesHelper.getStereotypePropertyValue(model, modelStereo, 'ontologyVersion')
            diag('  ontologyRootIRI=' + gotIri + '  ontologyVersion=' + gotVer)
            check('root IRI stored', gotIri != null && !gotIri.isEmpty() && gotIri.get(0) == rootIri)
            check('version stored', gotVer != null && !gotVer.isEmpty() && gotVer.get(0) == version)
            int alignedBefore = StereotypesHelper.getStereotypedElements(alignStereo).size()
            int modelsBefore = StereotypesHelper.getStereotypedElements(modelStereo).size()
            diag('  aligned elements=' + alignedBefore + '  instrumented models=' + modelsBefore)
            check('one aligned element', alignedBefore == 1)
            check('one instrumented model', modelsBefore == 1)

            // ---- UN-INSTRUMENT ----
            sm.createSession(prj, 'un-instrument model')
            new ArrayList(StereotypesHelper.getStereotypedElements(alignStereo)).each { el ->
                StereotypesHelper.removeStereotype(el, alignStereo)
            }
            new ArrayList(StereotypesHelper.getStereotypedElements(modelStereo)).each { el ->
                StereotypesHelper.removeStereotype(el, modelStereo)
            }
            sm.closeSession(prj)

            int alignedAfter = StereotypesHelper.getStereotypedElements(alignStereo).size()
            int modelsAfter = StereotypesHelper.getStereotypedElements(modelStereo).size()
            diag('  aligned after=' + alignedAfter + '  models after=' + modelsAfter)
            check('no aligned elements after un-instrument', alignedAfter == 0)
            check('no instrumented models after un-instrument', modelsAfter == 0)
            check('element clean', !StereotypesHelper.hasStereotype(testEl, alignStereo))
            check('model root clean', !StereotypesHelper.hasStereotype(model, modelStereo))

            pm.closeProjectNoSave(prj)
        } catch (Throwable t) {
            diagT('probe threw', t); fails << 'exception'
            try { if (prj != null) pm.closeProjectNoSave(prj) } catch (Throwable ignored) {}
        }
    }
    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== probe-instrument DONE ===')
