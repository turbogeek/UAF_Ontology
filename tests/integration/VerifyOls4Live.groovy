// VerifyOls4Live.groovy — proves the OLS4 client works INSIDE Cameo's JVM against the
// live public API: HttpClient + Jena atlas.json in the plugin classloader (where the
// groovy.json/FastStringUtils class of failure would surface), plus the capability guard.
import com.nomagic.magicdraw.plugins.PluginUtils

import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'VerifyOls4Live.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== verify-ols4-live START ===')
def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { fail('plugin not loaded'); diag('RESULT: FAIL'); return }
def cl = plugin.getClass().getClassLoader()

try {
    def srcCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.align.terms.Ols4TermSource', true, cl)
    def source = srcCls.getDeclaredConstructor().newInstance()
    diag('term source: ' + source.id())

    // Live search inside Cameo's JVM
    def results = source.search('Search', 'ncit', 5)
    diag('OLS4 search returned ' + results.size() + ' candidates (live)')
    if (results.isEmpty()) {
        fail('live OLS4 search returned nothing (network from Cameo JVM? proxy?)')
    } else {
        def top = results.get(0)
        diag('top: ' + top.oboId() + ' | ' + top.label() + ' | ont=' + top.ontologyPrefix())
        if (top.oboId() != 'NCIT:C54117') { fail('expected NCIT:C54117 top hit, got ' + top.oboId()) }

        // Live lookup -> Semantic_Type, then the capability guard
        def term = source.lookup(top.iri())
        if (!term.isPresent()) {
            fail('live lookup returned empty for ' + top.iri())
        } else {
            def st = term.get().semanticType()
            diag('Semantic_Type (live): ' + st)
            if (st != 'Activity') { fail('expected Activity, got ' + st) }
            def guardCls = Class.forName('com.nomagic.magicdraw.plugins.semantic.align.terms.CapabilityGuard', true, cl)
            def slotEnum = Class.forName('com.nomagic.magicdraw.plugins.semantic.align.terms.CapabilityGuard$Slot', true, cl)
            def capSlot = Enum.valueOf(slotEnum, 'CAPABILITY')
            def warning = guardCls.getMethod('validate', slotEnum, String).invoke(null, capSlot, st)
            if (warning.isPresent()) {
                diag('capability guard correctly FLAGGED Activity-in-Capability-slot: ' + warning.get().take(80))
            } else {
                fail('capability guard did not flag Activity in the capability slot')
            }
        }
    }
} catch (Throwable t) {
    diagT('UNCAUGHT (HttpClient or Jena JSON failed in Cameo classloader?)', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== verify-ols4-live DONE ===')
