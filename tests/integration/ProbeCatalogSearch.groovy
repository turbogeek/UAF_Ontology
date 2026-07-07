// ProbeCatalogSearch.groovy — confirm the breadth bundle concepts are alignable through the
// DEPLOYED SuggestionRanker: search "Force" (QUDT quantity kind) and "Organization" (CCO) and
// assert the results include qudt.org / commoncoreontologies.org IRIs. Read-only.
import com.nomagic.magicdraw.plugins.PluginUtils
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeCatalogSearch.log')
LOG.getParentFile().mkdirs(); try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t -> def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString()) }
def fails = []
def check = { String name, boolean cond -> diag((cond ? '[PASS] ' : '[FAIL] ') + name); if (!cond) fails << name }

diag('=== probe-catalog-search START ===')
try {
    def pluginObj = null; ClassLoader cl = null
    for (p in PluginUtils.getPlugins()) {
        if (p.getClass().getName().contains('SemanticAlignmentPlugin')) { pluginObj = p; cl = p.getClass().getClassLoader() }
    }
    check('plugin located', pluginObj != null)
    if (pluginObj == null) { diag('RESULT: FAIL'); return }

    def f = pluginObj.getClass().getDeclaredField('suggestionRanker'); f.setAccessible(true)
    def ranker = f.get(null)
    check('suggestionRanker present (catalog loaded)', ranker != null)
    if (ranker == null) { diag('RESULT: FAIL ' + fails); return }

    def search = { String q ->
        // searchVariants(seedText, stereotypes, narrowFrom, limit)
        def results = ranker.searchVariants(q, [], null, 15)
        def iris = results.collect { it.entry().iri() }
        diag('  "' + q + '" -> ' + results.size() + ' : ' + iris.take(6))
        return iris
    }

    def forceIris = search('Force')
    check('QUDT quantity kind surfaces for "Force"', forceIris.any { (it ?: '').contains('qudt.org') })

    def orgIris = search('Organization')
    check('CCO concept surfaces for "Organization"', orgIris.any { (it ?: '').toLowerCase().contains('commoncoreontologies') })

    // A governance term from the W3C bundle (PROV/ODRL/DPV).
    def govIris = search('Permission')
    check('governance vocab surfaces for "Permission"',
            govIris.any { (it ?: '').contains('odrl') || (it ?: '').contains('/prov') || (it ?: '').contains('dpv') })

    // openCAESAR IMCE systems-engineering foundation (from .owl).
    def imceIris = search('Component')
    check('IMCE SE concept surfaces for "Component"',
            imceIris.any { (it ?: '').contains('imce.jpl.nasa.gov') })

    diag('RESULT: ' + (fails.isEmpty() ? 'PASS' : ('FAIL ' + fails)))
} catch (Throwable t) { diagT('UNCAUGHT', t); diag('RESULT: FAIL') }
diag('=== probe-catalog-search DONE ===')
