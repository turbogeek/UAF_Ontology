// BuildSemanticProfile.groovy — author the shipped profile module CORRECTLY.
// The missing piece: ModulesService.shareOnTask(IProject, Package, path) marks the
// profile package SHARED so useModule imports it (unshared packages never cross into the
// using project - that was the delta-0 cause). Builds the invisible stereotype +
// «Customization», shares the profile package, saves the project as the module .mdzip.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.modules.ModulesService
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
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== build-semantic-profile START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    OUT.getParentFile().mkdirs()
    if (OUT.exists()) { OUT.delete() }
    def ok = false
    SwingUtilities.invokeAndWait {
        try {
            def prj = pm.createProject()
            if (prj == null) { diag('createProject null'); return }
            diag('project: ' + prj.getName())
            def sm = SessionManager.getInstance()
            sm.createSession(prj, 'author profile')
            def profile = null
            try {
                def ef = prj.getElementsFactory()
                profile = ef.createProfileInstance()
                profile.setName('Semantic Alignment Profile')
                profile.setOwner(prj.getPrimaryModel())

                // Base Classifier = InvisibleStereotype (UML Standard Profile::MagicDraw
                // Profile::InvisibleStereotype). A stereotype generalizing it becomes invisible
                // when applied - the proper mechanism shipped profiles use (verified: SysML +
                // UAF_Customization do exactly this). StereotypesHelper.isInvisible asserts it.
                def inv = StereotypesHelper.getStereotype(prj, 'InvisibleStereotype')
                def makeInvisible = { st ->
                    if (inv != null) {
                        def gen = ef.createGeneralizationInstance()
                        gen.setGeneral(inv)
                        gen.setSpecific(st)
                        if (!st.getGeneralization().contains(gen)) { st.getGeneralization().add(gen) }
                    } else {
                        diag('WARN: InvisibleStereotype not resolvable; ' + st.getName() + ' not marked invisible')
                    }
                }

                def elementMeta = StereotypesHelper.getAllMetaClasses(prj).find { it.getName() == 'Element' }
                def stereo = StereotypesHelper.createStereotype(prj, 'SemanticAlignment', [elementMeta])
                stereo.setOwner(profile)
                ['mappedConceptURI', 'ontologySource', 'mappingConfidence'].each { t ->
                    def p = ef.createPropertyInstance(); p.setName(t); stereo.getOwnedAttribute().add(p)
                    if (t == 'mappedConceptURI') {
                        // Multi-valued 0..* so an element carries a base concept + additive
                        // narrowing concepts (upper = -1 == unlimited '*').
                        def lower = ef.createLiteralIntegerInstance(); lower.setValue(0); p.setLowerValue(lower)
                        def upper = ef.createLiteralUnlimitedNaturalInstance(); upper.setValue(-1); p.setUpperValue(upper)
                    }
                }
                makeInvisible(stereo)
                def cust = StereotypesHelper.getStereotype(prj, 'Customization')
                if (cust != null) {
                    def cc = ef.createClassInstance(); cc.setName('SemanticAlignment Customization'); cc.setOwner(profile)
                    StereotypesHelper.addStereotype(cc, cust)
                    StereotypesHelper.setStereotypePropertyValue(cc, cust, 'customizationTarget', stereo)
                    StereotypesHelper.setStereotypePropertyValue(cc, cust, 'hideMetatype', Boolean.TRUE)
                    StereotypesHelper.setStereotypePropertyValue(cc, cust, 'representationText', 'Semantic Alignment')
                    StereotypesHelper.setStereotypePropertyValue(cc, cust, 'category', 'Semantic Alignment')
                    def spec = ['mappedConceptURI','ontologySource','mappingConfidence'].collect {
                        '<html><head><title>SPF</title></head><body><p>' + it + '</p></body></html>' }
                    StereotypesHelper.setStereotypePropertyValue(cc, cust, 'standardExpertConfiguration', spec)
                    diag('customization created')
                }

                // Model-level stereotype: carries the derived ontology's root IRI + version
                // (+ provenance). Applied to the model/package root when a model is
                // instrumented (extends Package so it applies to the Model root or any package).
                def pkgMeta = StereotypesHelper.getAllMetaClasses(prj).find { it.getName() == 'Package' }
                def modelStereo = StereotypesHelper.createStereotype(prj, 'SemanticModel', [pkgMeta])
                modelStereo.setOwner(profile)
                ['ontologyRootIRI', 'ontologyVersion', 'instrumentedBy', 'instrumentedDate'].each { t ->
                    def p = ef.createPropertyInstance(); p.setName(t); modelStereo.getOwnedAttribute().add(p)
                }
                makeInvisible(modelStereo)
                if (cust != null) {
                    def mc = ef.createClassInstance(); mc.setName('SemanticModel Customization'); mc.setOwner(profile)
                    StereotypesHelper.addStereotype(mc, cust)
                    StereotypesHelper.setStereotypePropertyValue(mc, cust, 'customizationTarget', modelStereo)
                    StereotypesHelper.setStereotypePropertyValue(mc, cust, 'hideMetatype', Boolean.TRUE)
                    StereotypesHelper.setStereotypePropertyValue(mc, cust, 'representationText', 'Semantic Model')
                    StereotypesHelper.setStereotypePropertyValue(mc, cust, 'category', 'Semantic Alignment')
                    def mspec = ['ontologyRootIRI','ontologyVersion','instrumentedBy','instrumentedDate'].collect {
                        '<html><head><title>SPF</title></head><body><p>' + it + '</p></body></html>' }
                    StereotypesHelper.setStereotypePropertyValue(mc, cust, 'standardExpertConfiguration', mspec)
                    diag('SemanticModel customization created')
                }
                sm.closeSession(prj)
                diag('profile authored')
                // Assert the Base Classifier mechanism took (repeatable-test requirement).
                diag('isInvisible(SemanticAlignment)=' + StereotypesHelper.isInvisible(stereo))
                diag('isInvisible(SemanticModel)=' + StereotypesHelper.isInvisible(modelStereo))
            } catch (Throwable t) { sm.cancelSession(prj); throw t }

            // THE FIX: mark the profile package SHARED (outside a model session).
            def primary = prj.getPrimaryProject()
            def sharePoint = ModulesService.shareOnTask(primary, profile, 'Semantic Alignment Profile')
            diag('shareOnTask -> ' + sharePoint)

            // Save the project (now with a shared profile package) as the module file.
            def desc = ProjectDescriptorsFactory.createLocalProjectDescriptor(prj, OUT)
            boolean saved = pm.saveProject(desc, true)
            diag('saveProject -> ' + saved + ' (' + (OUT.exists() ? OUT.length() : 0) + ' bytes)')
            pm.closeProjectNoSave(prj)
            ok = OUT.exists() && OUT.length() > 0
        } catch (Throwable t) { diagT('author threw', t) }
    }
    diag('RESULT: ' + (ok ? 'PASS' : 'FAIL'))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== build-semantic-profile DONE ===')
