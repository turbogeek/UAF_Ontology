// ProbeJvmModules.groovy — diagnostic probe (not a test)
// Dumps the running JVM's input arguments and whether java.desktop/jdk.swing.interop is
// exported to unnamed modules (needed by javafx.embed.swing on the classpath).
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeJvmModules.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

diag('java.version=' + System.getProperty('java.version') + ' vendor=' + System.getProperty('java.vendor'))
diag('java.home=' + System.getProperty('java.home'))
def args = ManagementFactory.getRuntimeMXBean().getInputArguments()
diag('input args (' + args.size() + '):')
args.each { diag('  ARG: ' + it) }
diag('JAVA_TOOL_OPTIONS env: ' + System.getenv('JAVA_TOOL_OPTIONS'))

def desktop = ModuleLayer.boot().findModule('java.desktop')
if (desktop.isPresent()) {
    def mod = desktop.get()
    def unnamed = this.getClass().getClassLoader().getUnnamedModule()
    diag('java.desktop present; jdk.swing.interop exported to script unnamed module: '
            + mod.isExported('jdk.swing.interop', unnamed))
    diag('jdk.swing.interop exported unconditionally: ' + mod.isExported('jdk.swing.interop'))
    // Also check against the PLUGIN's classloader unnamed module (where javafx lives)
    def plugin = com.nomagic.magicdraw.plugins.PluginUtils.getPlugins().find {
        it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
    }
    if (plugin != null) {
        def pluginUnnamed = plugin.getClass().getClassLoader().getUnnamedModule()
        diag('jdk.swing.interop exported to PLUGIN unnamed module: '
                + mod.isExported('jdk.swing.interop', pluginUnnamed))
        try {
            Class.forName('jdk.swing.interop.SwingInterOpUtils', false, plugin.getClass().getClassLoader())
            diag('Class.forName(jdk.swing.interop.SwingInterOpUtils) via plugin CL: OK')
        } catch (Throwable t) {
            diag('Class.forName via plugin CL FAILED: ' + t)
        }
    }
} else {
    diag('java.desktop module NOT FOUND in boot layer')
}
diag('probe done')
