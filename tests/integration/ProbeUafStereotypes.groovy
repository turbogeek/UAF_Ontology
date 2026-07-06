// ProbeUafStereotypes.groovy — reconnaissance (not a test)
// Loads a shipped UAF 1.3 sample and reports, for elements carrying UAF stereotypes:
// the stereotype name, its stable ID (UUID), and its owning profile - the data needed
// to auto-match applied stereotypes to UAF ontology concepts by UUID.
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat

final File SAMPLE = new File('E:\\Magic SW\\CMSoS26xR1pr\\samples\\UAF v1.3', 'UAF SAR Sample.mdzip')
final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeUafStereotypes.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-uaf-stereotypes START ===')
def app = Application.getInstance()
def pm = app.getProjectsManager()

try {
    def loaded = new boolean[1]
    SwingUtilities.invokeAndWait {
        try {
            def descriptor = ProjectDescriptorsFactory.createProjectDescriptor(SAMPLE.toURI())
            pm.loadProject(descriptor, true)
            loaded[0] = true
        } catch (Throwable t) { diagT('load failed', t) }
    }
    if (!loaded[0]) { diag('could not load sample'); diag('RESULT: FAIL'); return }
    Thread.sleep(2000)
    def project = app.getProject()
    diag('loaded: ' + project.getName())

    // Walk the model; collect distinct applied UAF stereotypes with their IDs.
    def seen = [:]  // stereotypeId -> [name, qn, profile, sampleElementName]
    def visit
    visit = { el ->
        try {
            StereotypesHelper.getStereotypes(el).each { st ->
                def id = st.getID()
                if (!seen.containsKey(id)) {
                    def prof = st.getOwner()
                    while (prof != null && !(prof.getHumanType()?.contains('Profile'))) { prof = prof.getOwner() }
                    seen[id] = [name: st.getName(), qn: st.getQualifiedName(),
                                profile: (prof?.respondsTo('getName') ? prof.getName() : '?'),
                                example: (el.respondsTo('getName') ? el.getName() : el.getHumanName())]
                }
            }
        } catch (Throwable ignored) {}
        el.getOwnedElement().each { visit(it) }
    }
    visit(project.getPrimaryModel())

    diag('distinct UAF stereotypes applied in sample: ' + seen.size())
    // Focus on the ones the user named: organization, post, capability, resource, battery
    def focus = seen.findAll { k, v -> v.name =~ /(?i)organization|post|person|capability|resource|performer|activity|battery|system/ }
    diag('--- focus stereotypes (name | ID | profile | example element) ---')
    focus.each { id, v -> diag('  ' + v.name + ' | ' + id + ' | ' + v.profile + ' | e.g. ' + v.example) }
    diag('--- all stereotype names (first 40) ---')
    seen.values().collect { it.name }.unique().sort().take(40).each { diag('  ' + it) }

    // Sample a couple of ActualOrganization / Post elements to show instance-level data
    def orgs = []
    def collectByStereo = { pattern ->
        def out = []
        def v2
        v2 = { el ->
            if (StereotypesHelper.getStereotypes(el).any { it.getName() =~ pattern }) { out << el }
            el.getOwnedElement().each { v2(it) }
        }
        v2(project.getPrimaryModel())
        out
    }
    def actualOrgs = collectByStereo(/(?i)actualorganization/)
    diag('ActualOrganization instances: ' + actualOrgs.size()
            + (actualOrgs ? ' e.g. ' + actualOrgs.take(3).collect { it.getName() } : ''))
    def batteries = collectByStereo(/(?i)battery|resource/)
    diag('Resource/Battery-ish instances: ' + batteries.size()
            + (batteries ? ' e.g. ' + batteries.take(5).collect { it.getName() + '<' + StereotypesHelper.getStereotypes(it).collect{it.getName()}.join(',') + '>' } : ''))

    diag('RESULT: PASS')
} catch (Throwable t) {
    diagT('UNCAUGHT', t)
    diag('RESULT: FAIL')
}
diag('=== probe-uaf-stereotypes DONE ===')
