// ProbePanelFields.groovy — diagnostic probe (not a test)
// Reads the SemanticBrowserPanel's private fields to find why FX updates are dropped:
// is fxReady true? do the panel's Label fields identity-match the labels in the scene?
import com.nomagic.magicdraw.plugins.PluginUtils

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbePanelFields.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }

def plugin = PluginUtils.getPlugins().find {
    it.getClass().getName() == 'com.nomagic.magicdraw.plugins.semantic.SemanticAlignmentPlugin'
}
if (plugin == null) { diag('plugin not loaded'); return }
def pluginCL = plugin.getClass().getClassLoader()

def hosts = []
def walk
walk = { java.awt.Component c ->
    if (c.getClass().getName().contains('SemanticBrowserPanel') && !hosts.contains(c)) { hosts << c }
    if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } }
}
SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { walk(it) } }
diag('SemanticBrowserPanel instances in window tree: ' + hosts.size())

def readField = { Object obj, String name ->
    try {
        def f = obj.getClass().getDeclaredField(name)
        f.setAccessible(true)
        return f.get(obj)
    } catch (Throwable t) { return '<err: ' + t.getClass().getSimpleName() + '>' }
}

hosts.eachWithIndex { host, i ->
    diag('--- panel[' + i + '] id=' + System.identityHashCode(host) + ' ---')
    ['fxReady', 'pendingSelection', 'project'].each { fn ->
        def v = readField(host, fn)
        diag('  ' + fn + ' = ' + (fn == 'project' && !(v instanceof String) && v != null ? v.getName() : String.valueOf(v)))
    }
    def selectionLabel = readField(host, 'selectionLabel')
    def statusBadge = readField(host, 'statusBadge')
    def jfx = readField(host, 'jfxPanel')
    diag('  selectionLabel id=' + System.identityHashCode(selectionLabel))
    diag('  statusBadge   id=' + System.identityHashCode(statusBadge))
    diag('  jfxPanel      id=' + System.identityHashCode(jfx) + (jfx == null ? '' : ' showing=' + jfx.isShowing()))

    if (jfx != null) {
        def platformCls = Class.forName('javafx.application.Platform', true, pluginCL)
        def latch = new CountDownLatch(1)
        def result = new AtomicReference()
        platformCls.getMethod('runLater', Runnable).invoke(null, {
            try {
                def scene = jfx.getScene()
                if (scene == null) { result.set('scene=null'); return }
                def badge = scene.getRoot().lookupAll('.label').find {
                    it.respondsTo('getText') && it.getText()?.startsWith('STATUS:')
                }
                result.set('scene badge id=' + System.identityHashCode(badge) + ' text=' + badge?.getText()
                        + ' | fieldBadgeTextNow=' + (statusBadge != null && statusBadge.respondsTo('getText') ? statusBadge.getText() : '<n/a>'))
            } catch (Throwable t) { result.set('<fx err: ' + t + '>') } finally { latch.countDown() }
        } as Runnable)
        latch.await(5, TimeUnit.SECONDS)
        diag('  ' + result.get())
    }
}
diag('probe done')
