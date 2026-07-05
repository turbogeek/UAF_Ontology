// IT8_QueryView.groovy
// =====================================================================================
// Integration test 8: the ontology view + query surfaces.
//   Phase A - /sparql REST: SELECT over catalog TBox + live ABox returns the fixture's
//             aligned elements; ASK confirms a sumo:MilitaryBase individual exists.
//   Phase B - SBVR tab (the Muggle default): click Refresh, assert the English
//             rendering contains "Echo Base is a Military Base." and the N-of-M status.
//   Phase C - Turtle tab renders and saves the full export.
// Requires IT1 fixture. Trace: v3 plan sections 2-3
// =====================================================================================
import com.nomagic.magicdraw.core.Application

import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

final String LOG_DIR = 'E:\\_Documents\\git\\UAF_Ontology\\logs'
final String REST = 'http://127.0.0.1:8766'

final File LOG = new File(LOG_DIR, 'IT8_QueryView.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def diagT = { String m, Throwable t ->
    def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); diag(m + '\n' + sw.toString())
}
boolean pass = true
def fail = { String m -> pass = false; diag('FAIL: ' + m) }

def http = { String method, String path, String body ->
    def conn = (HttpURLConnection) new URL(REST + path).openConnection()
    conn.setRequestMethod(method)
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(30000)
    if (body != null) {
        conn.setDoOutput(true)
        conn.getOutputStream().withCloseable { it.write(body.getBytes('UTF-8')) }
    }
    def code = conn.getResponseCode()
    def text = (code < 400 ? conn.getInputStream() : conn.getErrorStream())?.getText('UTF-8')
    [code: code, body: text ?: '']
}
def findByName = { String name ->
    def result = new AtomicReference()
    def walk
    walk = { java.awt.Component c ->
        if (result.get() != null) { return }
        if (name == c.getName()) { result.set(c); return }
        if (c instanceof java.awt.Container) { c.getComponents().each { walk(it) } }
    }
    SwingUtilities.invokeAndWait { java.awt.Window.getWindows().each { w -> if (result.get() == null) { walk(w) } } }
    return result.get()
}
def readOnEdt = { Closure work ->
    def result = new AtomicReference()
    SwingUtilities.invokeAndWait { result.set(work.call()) }
    return result.get()
}

diag('=== IT8 query-view START ===')
def app = null
try { app = Application.getInstance() } catch (Throwable ignored) {}
if (app == null) { diag('Not running inside CATIA Magic.'); diag('RESULT: SKIP'); return }
if (app.getProject() == null) { fail('No open project.'); diag('RESULT: FAIL'); return }

try {
    // --- Phase A: /sparql --------------------------------------------------------------
    def select = http('POST', '/sparql', '''
        SELECT ?element ?label WHERE {
          ?element a ?type ; <http://www.w3.org/2000/01/rdf-schema#label> ?label .
          FILTER(STRSTARTS(STR(?element), "http://purl.org/uaf/project/"))
        }''')
    if (select.code != 200) {
        fail('/sparql SELECT -> HTTP ' + select.code + ': ' + select.body.take(200))
    } else if (!select.body.contains('EchoBase')) {
        fail('/sparql SELECT missing fixture element EchoBase: ' + select.body.take(300))
    } else {
        diag('/sparql SELECT OK (' + select.body.length() + ' bytes, contains EchoBase)')
    }

    def ask = http('POST', '/sparql',
            'ASK { ?s a <http://www.ontologyportal.org/SUMO.owl#MilitaryBase> }')
    if (ask.code == 200 && ask.body.contains('true')) {
        diag('/sparql ASK OK: a sumo:MilitaryBase individual exists')
    } else {
        fail('/sparql ASK -> ' + ask.code + ' ' + ask.body.take(200))
    }

    // --- Phase B: SBVR tab (Muggle default) --------------------------------------------
    def refresh = findByName('semantic.sbvrRefresh')
    def sbvrView = findByName('semantic.sbvrView')
    if (refresh == null || sbvrView == null) {
        fail('ontology view components not found (window not registered?)')
    } else {
        SwingUtilities.invokeAndWait { refresh.doClick() }
        String text = null
        long deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            text = readOnEdt { sbvrView.getText() }
            if (text != null && text.contains('Military Base')) { break }
            Thread.sleep(300)
        }
        if (text != null && text.contains('Instance: Echo Base is a Military Base.')) {
            diag('SBVR view OK: English rendering contains the fixture sentence')
        } else {
            fail('SBVR view text (first 300): ' + (text == null ? 'null' : text.take(300)))
        }
    }

    // --- Phase C: Turtle tab -------------------------------------------------------------
    def turtleRefresh = findByName('semantic.turtleRefresh')
    def turtleView = findByName('semantic.turtleView')
    if (turtleRefresh == null || turtleView == null) {
        fail('turtle tab components not found')
    } else {
        SwingUtilities.invokeAndWait { turtleRefresh.doClick() }
        String ttl = null
        long deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            ttl = readOnEdt { turtleView.getText() }
            if (ttl != null && ttl.contains('MilitaryBase')) { break }
            Thread.sleep(300)
        }
        if (ttl != null && ttl.contains('MilitaryBase') && ttl.contains('hasPart')) {
            diag('Turtle view OK')
        } else {
            fail('Turtle view text (first 200): ' + (ttl == null ? 'null' : ttl.take(200)))
        }
        def saved = new File(new File(System.getProperty('user.home'), '.semantic_alignment_plugin'), 'ontology-export.ttl')
        if (saved.exists() && saved.length() > 0) {
            diag('full Turtle export saved: ' + saved + ' (' + saved.length() + ' bytes)')
        } else {
            fail('full Turtle export file missing: ' + saved)
        }
    }
} catch (Throwable t) {
    diagT('UNCAUGHT in IT8', t)
    pass = false
}

diag('RESULT: ' + (pass ? 'PASS' : 'FAIL'))
diag('=== IT8 DONE ===')
