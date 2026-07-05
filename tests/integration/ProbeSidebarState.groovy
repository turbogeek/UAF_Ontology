// ProbeSidebarState.groovy — diagnostic probe (not a test)
// Counts SemanticBrowserPanel-hosted JFXPanels and dumps every Label/Button text in
// each panel's scene, to determine why the status badge did not visibly update.
import com.nomagic.magicdraw.plugins.PluginUtils

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeSidebarState.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { diag('plugin not loaded'); return }
def pluginCL = plugin.getClass().getClassLoader()

// Collect ALL JFXPanels with a SemanticBrowserPanel ancestor
def panels = []
def walk
walk = { java.awt.Component c, List out ->
    if (c.getClass().getName().endsWith('JFXPanel')) {
        def p = c.getParent()
        while (p != null) {
            if (p.getClass().getName().contains('SemanticBrowserPanel')) { out << [fx: c, host: p]; break }
            p = p.getParent()
        }
    }
    if (c instanceof java.awt.Container) { c.getComponents().each { walk(it, out) } }
}
SwingUtilities.invokeAndWait {
    java.awt.Window.getWindows().each { w -> walk(w, panels) }
}
diag('SemanticBrowserPanel-hosted JFXPanels found: ' + panels.size())
panels.eachWithIndex { entry, i ->
    diag('panel[' + i + '] host=' + System.identityHashCode(entry.host)
            + ' fx=' + System.identityHashCode(entry.fx)
            + ' showing=' + entry.fx.isShowing() + ' size=' + entry.fx.getWidth() + 'x' + entry.fx.getHeight())
}

def platformCls = Class.forName('javafx.application.Platform', true, pluginCL)
panels.eachWithIndex { entry, i ->
    def latch = new CountDownLatch(1)
    def result = new AtomicReference()
    platformCls.getMethod('runLater', Runnable).invoke(null, {
        try {
            def scene = entry.fx.getScene()
            if (scene == null) { result.set(['<no scene>']); return }
            def texts = []
            scene.getRoot().lookupAll('.label').each { n ->
                if (n.respondsTo('getText')) { texts << ('LABEL: ' + n.getText()) }
            }
            scene.getRoot().lookupAll('.button').each { n ->
                if (n.respondsTo('getText')) { texts << ('BUTTON: ' + n.getText() + ' disabled=' + n.isDisabled()) }
            }
            scene.getRoot().lookupAll('.text-area').each { n ->
                if (n.respondsTo('getText')) {
                    def t = n.getText()
                    texts << ('TEXTAREA: ' + (t == null ? '' : t.replaceAll('[\\r\\n]+', ' | ')).take(300))
                }
            }
            result.set(texts)
        } catch (Throwable t) {
            result.set(['<error: ' + t + '>'])
        } finally { latch.countDown() }
    } as Runnable)
    latch.await(5, TimeUnit.SECONDS)
    def texts = result.get()
    if (texts == null) { diag('panel[' + i + '] FX read timed out') }
    else { texts.each { diag('panel[' + i + '] ' + it) } }
}
diag('probe done')
