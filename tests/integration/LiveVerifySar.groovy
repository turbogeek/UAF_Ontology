// LiveVerifySar.groovy — live verification of the bug-fix set against the open
// "Mission Engineering Sample (SAR)" UAF project, exercising the DEPLOYED plugin classes.
// Checks: (1) profile mounts as a read-only USED module (NOT embedded in the project);
// (2) SemanticAlignment stereotype is invisible (isInvisible==true); (3) "Extreme Weather
// Conditions" resolves to uaf:Challenge as the base concept via the deployed resolver;
// (4) multi-valued storage: setSemanticConcepts([base, extra1, extra2]) reads back all
// three, base first. Modifies a throwaway temp element only; never saves the project.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'LiveVerifySar.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

// Bounded recursive search for a NamedElement by exact name.
def findByName
findByName = { Element owner, String target, budget ->
    if (budget[0] <= 0) { return null }
    for (Element c : owner.getOwnedElement()) {
        budget[0]--
        if (c.respondsTo('getName') && target.equals(c.getName())) { return c }
        def hit = findByName(c, target, budget)
        if (hit != null) { return hit }
    }
    return null
}

diag('=== live-verify-sar START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()
try {
    ClassLoader cl = null
    def pluginObj = null
    for (p in PluginUtils.getPlugins()) {
        if (p.getClass().getName().contains('SemanticAlignmentPlugin')) { pluginObj = p; cl = p.getClass().getClassLoader() }
    }
    check('plugin classloader located', cl != null)
    if (cl == null) { diag('RESULT: FAIL ' + fails); return }
    def SM = Class.forName('com.nomagic.magicdraw.plugins.semantic.metadata.StereotypeManager', true, cl)

    SwingUtilities.invokeAndWait {
        def prj = null
        def temp = null
        try {
            prj = app.getProject()
            if (prj == null) { fails << 'no project open'; diag('FAIL: no project open (still loading?)'); return }
            diag('active project: ' + prj.getName())
            check('SAR sample is open', (prj.getName() ?: '').toLowerCase().contains('sar')
                    || (prj.getName() ?: '').toLowerCase().contains('mission'))

            def model = prj.getPrimaryModel()
            def challenge = findByName(model, 'Extreme Weather Conditions', [200000] as int[])
            check('"Extreme Weather Conditions" element found', challenge != null)
            if (challenge != null) {
                def stns = StereotypesHelper.getStereotypes(challenge).collect { it.getName() }
                diag('  its stereotypes: ' + stns)
                check('element carries a UAF Challenge stereotype', stns.any { it == 'Challenge' })
            }

            // (1)+(2) Mount the profile via the DEPLOYED code, assert used-module + invisible.
            boolean avail = SM.getMethod('ensureProfileAvailable', com.nomagic.magicdraw.core.Project).invoke(null, prj)
            check('ensureProfileAvailable (deployed) mounted the module', avail)
            def sa = StereotypesHelper.getStereotype(prj, 'SemanticAlignment')
            check('SemanticAlignment stereotype resolves', sa != null)
            if (sa != null) {
                // A used (mounted) module's elements are read-only; an embedded in-project
                // profile would be editable. read-only => USED module, not embedded.
                check('stereotype is READ-ONLY (used module, not embedded)', !sa.isEditable())
                check('stereotype isInvisible == true (Base Classifier)', StereotypesHelper.isInvisible(sa))
            }
            // A mounted used-module profile appears in the model tree but is READ-ONLY (like
            // UAF Profile); an EMBEDDED profile would be editable. So "not embedded" == the
            // profile package, if present, is read-only.
            def profilePkg = model.getOwnedElement().find {
                it.respondsTo('getName') && it.getName() == 'Semantic Alignment Profile'
            }
            check('profile is a read-only used module (not an editable embed)',
                    profilePkg == null || !profilePkg.isEditable())

            // (3) Base resolution via the deployed UafConceptResolver static field.
            String baseIri = null
            try {
                def f = pluginObj.getClass().getDeclaredField('uafResolver'); f.setAccessible(true)
                def resolver = f.get(null)
                if (resolver != null && challenge != null) {
                    def challStereo = StereotypesHelper.getStereotypes(challenge).find { it.getName() == 'Challenge' }
                    if (challStereo != null) {
                        def concept = resolver.resolve('Challenge', challStereo.getID())
                        baseIri = (concept == null) ? null : concept.iri()
                        diag('  resolved base concept: ' + baseIri)
                    }
                }
            } catch (Throwable t) { diagT('resolver reflection failed', t) }
            check('base concept resolves to a uaf:Challenge IRI',
                    baseIri != null && baseIri.toLowerCase().contains('challenge'))
            if (baseIri == null) { baseIri = 'http://uaf/Challenge' } // fallback for the write test

            // (4) Multi-valued storage on a THROWAWAY temp element (never saved).
            def sm = SessionManager.getInstance()
            sm.createSession(prj, 'LiveVerify temp write')
            try {
                temp = prj.getElementsFactory().createClassInstance()
                temp.setName('ZZ_SemanticProbeTemp'); temp.setOwner(model)
                def concepts = [baseIri, 'http://example.org/Extreme', 'http://example.org/Weather']
                SM.getMethod('setSemanticConcepts', Element, java.util.List).invoke(null, temp, concepts)
                sm.closeSession(prj)
            } catch (Throwable t) { sm.cancelSession(prj); throw t }
            def stored = SM.getMethod('getMappedConcepts', Element).invoke(null, temp)
            diag('  stored concepts: ' + stored)
            check('three concepts stored (multi-valued)', stored != null && stored.size() == 3)
            check('base concept is index 0', stored != null && stored.size() > 0 && stored.get(0) == baseIri)

            // cleanup the temp element (leave the sample clean in-memory; do not save)
            sm.createSession(prj, 'LiveVerify cleanup')
            try {
                com.nomagic.magicdraw.openapi.uml.ModelElementsManager.getInstance().removeElement(temp)
                sm.closeSession(prj)
                diag('temp element removed')
            } catch (Throwable t) { sm.cancelSession(prj); diagT('cleanup failed', t) }
        } catch (Throwable t) {
            diagT('probe body threw', t); fails << 'exception'
        }
    }
    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== live-verify-sar DONE ===')
