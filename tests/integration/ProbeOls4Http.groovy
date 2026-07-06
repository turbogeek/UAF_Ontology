// ProbeOls4Http.groovy — diagnose the exact IOException class calling OLS4 from Cameo's
// JVM: proxy (ConnectException/UnknownHost) vs TLS trust (SSLHandshake/PKIX). Tries a
// plain client, then the system-proxy selector, and reports the full cause chain.
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.ProxySelector
import java.text.SimpleDateFormat

final File LOG = new File('E:\\_Documents\\git\\UAF_Ontology\\logs', 'ProbeOls4Http.log')
LOG.getParentFile().mkdirs()
try { LOG.bytes = new byte[0] } catch (Throwable ignored) {}
final SimpleDateFormat TS = new SimpleDateFormat('HH:mm:ss.SSS')
def diag = { String m -> String l = TS.format(new Date()) + '  ' + m; println l; try { LOG << (l + '\n') } catch (Throwable ignored) {} }
def chain = { Throwable t -> def s=[]; def c=t; while(c!=null){ s << (c.getClass().getName()+': '+c.getMessage()); c=c.getCause() }; s.join('  <-  ') }

diag('=== probe-ols4-http START ===')
diag('java proxy props: http=' + System.getProperty('http.proxyHost') + ':' + System.getProperty('http.proxyPort')
        + ' https=' + System.getProperty('https.proxyHost') + ':' + System.getProperty('https.proxyPort')
        + ' useSystemProxies=' + System.getProperty('java.net.useSystemProxies'))
def url = 'https://www.ebi.ac.uk/ols4/api/search?q=Search&rows=1'
def req = HttpRequest.newBuilder(URI.create(url)).GET().build()

diag('-- attempt 1: plain HttpClient --')
try {
    def c = HttpClient.newHttpClient()
    def r = c.send(req, HttpResponse.BodyHandlers.ofString())
    diag('OK status=' + r.statusCode() + ' bytes=' + r.body().length())
} catch (Throwable t) { diag('FAILED: ' + chain(t)) }

diag('-- attempt 2: HttpClient with system ProxySelector --')
try {
    def c = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build()
    def r = c.send(req, HttpResponse.BodyHandlers.ofString())
    diag('OK status=' + r.statusCode() + ' bytes=' + r.body().length())
} catch (Throwable t) { diag('FAILED: ' + chain(t)) }

// What proxy would the default selector choose for this URL?
try {
    def sel = ProxySelector.getDefault()
    diag('default ProxySelector for url: ' + (sel == null ? 'null' : sel.select(URI.create(url))))
} catch (Throwable t) { diag('proxy select failed: ' + chain(t)) }

diag('env HTTPS_PROXY=' + System.getenv('HTTPS_PROXY') + ' HTTP_PROXY=' + System.getenv('HTTP_PROXY'))

diag('-- attempt 3: HttpClient forced HTTP/1.1 --')
try {
    def c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    def r = c.send(req, HttpResponse.BodyHandlers.ofString())
    diag('OK status=' + r.statusCode() + ' bytes=' + r.body().length() + ' ver=' + r.version())
} catch (Throwable t) { diag('FAILED: ' + chain(t)) }

diag('=== probe-ols4-http DONE ===')
