// ProbeCustomizationPatterns.groovy — study how shipped profiles implement the
// invisible-stereotype + Customization pattern, from the live UAF profiles loaded in the
// open project. Extracts: (1) the «Customization» stereotype definition + its properties,
// (2) real Customization instances from UAF_Customization with their tag values, (3) what
// distinguishes stereotypes where StereotypesHelper.isInvisible()==true.
import com.nomagic.magicdraw.core.Application
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeCustomizationPatterns.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }

diag('=== probe-customization-patterns START ===')
def app = Application.getInstance()
def project = app.getProject()
if (project == null) { diag('no project'); return }
diag('project: ' + project.getName())

try {
    // (1) The «Customization» stereotype definition + its properties (how you configure a customization)
    def customizationStereo = StereotypesHelper.getStereotype(project, 'Customization')
    if (customizationStereo != null) {
        diag('--- «Customization» stereotype: ' + customizationStereo.getQualifiedName() + ' ---')
        customizationStereo.getOwnedAttribute().each { p ->
            def type = p.getType() ? p.getType().getName() : '?'
            diag('  property: ' + p.getName() + ' : ' + type)
        }
    } else {
        diag('«Customization» stereotype not directly resolvable by simple name')
    }

    // (2) Real Customization instances applied in the loaded UAF_Customization module
    diag('--- Customization instances in the model (first 8) ---')
    int shown = 0
    def visit
    visit = { el ->
        if (shown >= 8) { return }
        try {
            def sts = StereotypesHelper.getStereotypes(el)
            if (sts.any { it.getName() == 'Customization' }) {
                shown++
                def cust = sts.find { it.getName() == 'Customization' }
                def name = el.respondsTo('getName') ? el.getName() : el.getHumanName()
                diag('  Customization on "' + name + '":')
                cust.getOwnedAttribute().each { p ->
                    def vals = StereotypesHelper.getStereotypePropertyValue(el, cust, p.getName())
                    if (vals != null && !vals.isEmpty()) {
                        def shownVals = vals.collect { v -> v.respondsTo('getName') ? v.getName() : String.valueOf(v) }
                        diag('    ' + p.getName() + ' = ' + shownVals)
                    }
                }
            }
        } catch (Throwable ignored) {}
        el.getOwnedElement().each { visit(it) }
    }
    visit(project.getPrimaryModel())
    diag('  (customization instances shown: ' + shown + ')')

    // (3) What makes a stereotype invisible? Scan UAF stereotypes for isInvisible==true.
    diag('--- invisible stereotypes among UAF (first 10) ---')
    int inv = 0
    def profiles = StereotypesHelper.getAllStereotypes(project)
    profiles.take(4000).each { st ->
        try {
            if (inv < 10 && StereotypesHelper.isInvisible(st)) {
                inv++
                diag('  INVISIBLE: ' + st.getName() + ' (in ' + (st.getOwner()?.respondsTo('getName') ? st.getOwner().getName() : '?') + ')')
            }
        } catch (Throwable ignored) {}
    }
    diag('  (invisible stereotypes found: ' + inv + ' of ' + profiles.size() + ' total)')

    diag('RESULT: DONE')
} catch (Throwable t) { diagT('UNCAUGHT', t) }
diag('=== probe-customization-patterns DONE ===')
