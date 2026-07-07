// IT0_PluginLoaded.groovy
// =====================================================================================
// Integration test 0: verifies the Semantic Alignment plugin (v2.2.0) is loaded in the
// running CATIA Magic instance, that its bundled Jena/JavaFX classes resolve through the
// plugin's own classloader (ownClassloader="true"), and that the DiagnosticLog journal
// exists with a LIFECYCLE init entry. Run via the REST test harness (POST /run).
// Trace: PLG-REQ deployment preconditions for IT1..IT5
// =====================================================================================
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.plugins.PluginUtils

import java.text.SimpleDateFormat

final String LOG_DIR = System.getProperty('semantic.it.logdir', new File(System.getProperty('user.home'), '.semantic_alignment_plugin/it-logs').getAbsolutePath())
final File LOG = new File(LOG_DIR, 'IT0_PluginLoaded.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

diag('=== IT0 plugin-loaded START ===')

// Requirement: when launched outside CATIA Magic, detect it safely and skip.
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) {
    diag('Not running inside CATIA Magic - nothing to test here.')
    diag('RESULT: SKIP')
    return
}

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) {
    fail('SemanticAlignmentPlugin is not loaded. Deployed to plugins/com.nomagic.magicdraw.plugins.semantic and restarted Cameo?')
    diag('Loaded plugin classes: ' + PluginUtils.getPlugins().collect { it.getClass().getName() }.sort().join(', '))
} else {
    def pluginCL = plugin.getClass().getClassLoader()
    diag('plugin loaded: ' + plugin.getClass().getName() + ' | classloader=' + pluginCL)
    def dir = plugin.respondsTo('getDescriptor') ? plugin.getDescriptor()?.getPluginDirectory() : null
    diag('plugin directory: ' + dir)

    // Every bundled dependency must resolve through the plugin's classloader.
    ['com.nomagic.magicdraw.plugins.semantic.SemanticRDFExporter',
     'com.nomagic.magicdraw.plugins.semantic.SHACLValidator',
     'com.nomagic.magicdraw.plugins.semantic.SBVREngine',
     'com.nomagic.magicdraw.plugins.semantic.DiagnosticLog',
     'org.apache.jena.rdf.model.ModelFactory',
     'org.apache.jena.shacl.ShaclValidator'].each { cn ->
        try {
            Class.forName(cn, true, pluginCL)
            diag('class OK: ' + cn)
        } catch (Throwable t) {
            fail('class not resolvable via plugin classloader: ' + cn + ' -> ' + t)
        }
    }

    // The diagnostic journal is the assertable GUI contract for IT3..IT5.
    def journal = new File(new File(System.getProperty('user.home'), '.semantic_alignment_plugin'), 'semantic-plugin.log')
    if (!journal.exists()) {
        fail('diagnostic journal missing: ' + journal)
    } else {
        def text = journal.getText('UTF-8')
        if (!text.contains('| LIFECYCLE |')) {
            fail('journal has no LIFECYCLE entries: ' + journal)
        } else {
            diag('journal OK: ' + journal + ' (' + journal.length() + ' bytes)')
        }
    }

    // SBVR smoke test through the plugin classloader (proves plugin code executes).
    try {
        def engine = Class.forName('com.nomagic.magicdraw.plugins.semantic.SBVREngine', true, pluginCL)
                .getDeclaredConstructor().newInstance()
        String sbvr = engine.generatePlainSBVR('EchoBase', 'sumo:MilitaryBase', null, null)
        if (sbvr == 'Instance: Echo Base is a Military Base.') {
            diag('SBVR smoke test OK: ' + sbvr)
        } else {
            fail('SBVR smoke test unexpected output: ' + sbvr)
        }
    } catch (Throwable t) {
        fail('SBVR smoke test threw: ' + t)
    }
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT0 DONE ===')
