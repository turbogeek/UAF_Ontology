package com.nomagic.magicdraw.plugins.semantic.service;

import com.nomagic.magicdraw.plugins.semantic.SBVREngine;
import com.nomagic.magicdraw.plugins.semantic.SHACLValidator;
import com.nomagic.magicdraw.plugins.semantic.align.CatalogLoader;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptCategoryIndex;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.LayerRouter;
import com.nomagic.magicdraw.plugins.semantic.align.ScopeContext;
import com.nomagic.magicdraw.plugins.semantic.align.StereotypeRouter;
import com.nomagic.magicdraw.plugins.semantic.align.SuggestionRanker;
import com.nomagic.magicdraw.plugins.semantic.align.UafConceptResolver;
import com.nomagic.magicdraw.plugins.semantic.align.terms.AlignmentCandidate;
import com.nomagic.magicdraw.plugins.semantic.align.terms.Ols4TermSource;
import com.nomagic.magicdraw.plugins.semantic.align.terms.OntoPortalTermSource;
import com.nomagic.magicdraw.plugins.semantic.align.terms.TermSource;
import com.nomagic.magicdraw.plugins.semantic.reasoning.JenaRulesReasonerAdapter;
import com.nomagic.magicdraw.plugins.semantic.reasoning.Reasoners;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

/**
 * Standalone, OUT-OF-PROCESS Semantic Catalog Service. Owns the heavy Jena models (the
 * ~thousands-of-concepts catalog index, the union TBox model, SPARQL, the reasoner) and the
 * online term sources (OLS4 / TIB / OntoPortal), exposing them over a localhost REST API so
 * the Cameo plugin can stay a thin client and Cameo's JVM never carries the ontology heap.
 *
 * Runs LOCALLY (auto-started by the plugin as a child process) or on a SHARED SERVER (run this
 * jar on a host; point the plugin at it via -Dsemantic.plugin.service.url). All classes it
 * uses are Cameo-free; it depends only on Jena + the JDK. No System.exit on request paths.
 *
 * Endpoints (localhost): GET /health; POST /suggest; POST /search; GET /resolve; POST /sparql;
 * POST /reason; POST /catalog/reload.
 * Trace: design/service_architecture.md (memory: move ontology work out of Cameo)
 */
public final class SemanticCatalogService {

    private static volatile SuggestionRanker ranker;
    private static volatile UafConceptResolver resolver;
    private static volatile Model catalogModel;
    private static volatile List<TermSource> onlineSources = List.of();
    private static final SBVREngine SBVR = new SBVREngine();

    private static File pluginDir;    // holds catalog/, tbox/, shapes
    private static File catalogDir;   // explicit catalog dir override (else pluginDir/catalog)

    private SemanticCatalogService() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8767;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[i + 1]);
            } else if ("--plugin-dir".equals(args[i])) {
                pluginDir = new File(args[i + 1]);
            } else if ("--catalog-dir".equals(args[i])) {
                catalogDir = new File(args[i + 1]);
            }
        }
        port = Integer.getInteger("semantic.service.port", port);
        String pd = System.getProperty("semantic.service.plugin.dir");
        if (pd != null) {
            pluginDir = new File(pd);
        }

        // Initialize Jena's subsystems deterministically before any RDF/ARQ use (ServiceLoader-
        // based; must run explicitly in a plain multi-jar classpath).
        org.apache.jena.sys.JenaSystem.init();
        Reasoners.register(new JenaRulesReasonerAdapter());
        onlineSources = buildOnlineSources();
        loadCatalog();

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/health", SemanticCatalogService::handleHealth);
        server.createContext("/suggest", SemanticCatalogService::handleSuggest);
        server.createContext("/search", SemanticCatalogService::handleSearch);
        server.createContext("/resolve", SemanticCatalogService::handleResolve);
        server.createContext("/sparql", SemanticCatalogService::handleSparql);
        server.createContext("/reason", SemanticCatalogService::handleReason);
        server.createContext("/catalog/reload", SemanticCatalogService::handleReload);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "semantic-catalog-service");
            t.setDaemon(false);
            return t;
        }));
        server.start();
        System.out.println("[semantic-catalog-service] listening on http://127.0.0.1:" + port
                + " (pluginDir=" + pluginDir + ", concepts="
                + (ranker == null ? 0 : "ready") + ")");
        // Keep the JVM alive; the executor threads are non-daemon.
    }

    // --- catalog + sources -----------------------------------------------------------------

    private static synchronized String loadCatalog() {
        File dir = catalogDir != null ? catalogDir : pluginDir;
        CatalogLoader.LoadedCatalog catalog = catalogDir != null
                ? CatalogLoader.loadAll(catalogDir)
                : CatalogLoader.loadMerged(pluginDir);
        catalogModel = catalog.model();
        // Classify every concept by BFO upper category once, so /suggest can be construct-kind
        // aware (behavior->occurrent, structure->object). Timed and logged - the four traversals
        // are the heaviest part of load, so a regression here is visible.
        long t0 = System.nanoTime();
        ConceptCategoryIndex catIndex = ConceptCategoryIndex.build(catalog.model());
        long catMs = (System.nanoTime() - t0) / 1_000_000L;
        LayerRouter layers = LayerRouter.load(pluginDir);
        ranker = new SuggestionRanker(catalog.index(), StereotypeRouter.load(pluginDir), catIndex, layers);
        resolver = UafConceptResolver.fromModel(catalog.model());
        String summary = "{\"concepts\":" + catalog.index().size()
                + ",\"tboxTriples\":" + catalog.model().size()
                + ",\"uafConcepts\":" + resolver.size()
                + ",\"bfoClassified\":" + catIndex.size()
                + ",\"bfoClassifyMs\":" + catMs
                + ",\"catalogDir\":\"" + json(String.valueOf(dir)) + "\"}";
        System.out.println("[semantic-catalog-service] catalog loaded: " + summary);
        return summary;
    }

    private static List<TermSource> buildOnlineSources() {
        List<TermSource> sources = new ArrayList<>();
        String cfg = System.getProperty("semantic.plugin.ols.endpoints");
        if (cfg != null && !cfg.isBlank()) {
            for (String base : cfg.split(",")) {
                if (!base.isBlank()) {
                    sources.add(new Ols4TermSource(base.trim()));
                }
            }
        } else {
            sources.add(new Ols4TermSource());
            sources.add(new Ols4TermSource("https://api.terminology.tib.eu/api"));
        }
        Properties portal = new Properties();
        try {
            File f = new File(System.getProperty("user.home"), ".semantic_alignment_plugin/ontoportal.properties");
            if (f.isFile()) {
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    portal.load(in);
                }
            }
        } catch (Exception ignored) {
            // portals off
        }
        addPortal(sources, portal, "bioportal", "https://data.bioontology.org");
        addPortal(sources, portal, "industryportal", "https://industryportal.enit.fr");
        addPortal(sources, portal, "matportal", "https://matportal.org");
        return sources;
    }

    private static void addPortal(List<TermSource> sources, Properties portal, String name, String defUrl) {
        String key = prop(portal, name, "apikey", null);
        if (key == null || key.isBlank()) {
            return;
        }
        sources.add(new OntoPortalTermSource(name, prop(portal, name, "url", defUrl),
                key, prop(portal, name, "ontologies", null)));
    }

    private static String prop(Properties portal, String name, String suffix, String fallback) {
        String sys = System.getProperty("semantic.plugin.ontoportal." + name + "." + suffix);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String v = portal.getProperty(name + "." + suffix);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    // --- endpoints -------------------------------------------------------------------------

    private static void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"service\":\"semantic-catalog-service\",\"ready\":" + (ranker != null)
                + ",\"tboxTriples\":" + (catalogModel == null ? 0 : catalogModel.size())
                + ",\"onlineSources\":" + onlineSources.size() + "}");
    }

    private static void handleReload(HttpExchange ex) throws IOException {
        try {
            respond(ex, 200, loadCatalog());
        } catch (Exception e) {
            respond(ex, 500, err(e));
        }
    }

    /** POST {name, stereotypes[], narrowFrom?, limit} -> suggestions for a selected element. */
    private static void handleSuggest(HttpExchange ex) throws IOException {
        try {
            JsonObject req = readJson(ex);
            SuggestionRanker r = ranker;
            if (r == null) {
                respond(ex, 503, "{\"error\":\"catalog not ready\"}");
                return;
            }
            String name = str(req, "name");
            List<String> stereotypes = strList(req, "stereotypes");
            String narrowFrom = str(req, "narrowFrom");
            int limit = req.hasKey("limit") ? (int) req.get("limit").getAsNumber().value().doubleValue() : 12;
            ScopeContext scope = parseScope(req);
            List<ConceptSuggestion> hits = r.searchVariants(name, stereotypes, narrowFrom, limit, scope);
            respond(ex, 200, suggestionsJson(hits, name));
        } catch (Exception e) {
            respond(ex, 400, err(e));
        }
    }

    /** POST {query, ontologyFilter?, limit, online?} -> local (and optionally online) results. */
    private static void handleSearch(HttpExchange ex) throws IOException {
        try {
            JsonObject req = readJson(ex);
            String query = str(req, "query");
            if (query == null || query.isBlank()) {
                respond(ex, 400, "{\"error\":\"query required\"}");
                return;
            }
            int limit = req.hasKey("limit") ? (int) req.get("limit").getAsNumber().value().doubleValue() : 12;
            boolean online = req.hasKey("online") && req.get("online").isBoolean()
                    && req.get("online").getAsBoolean().value();
            if (online) {
                String filter = str(req, "ontologyFilter");
                LinkedHashMap<String, AlignmentCandidate> merged = new LinkedHashMap<>();
                for (TermSource src : onlineSources) {
                    try {
                        for (AlignmentCandidate c : src.search(query, filter, Math.max(1, limit / 2))) {
                            if (c.iri() != null && !c.iri().isBlank()) {
                                merged.putIfAbsent(c.iri(), c);
                            }
                        }
                    } catch (Exception ignored) {
                        // one source failing must not sink the rest
                    }
                }
                respond(ex, 200, candidatesJson(new ArrayList<>(merged.values())));
            } else {
                SuggestionRanker r = ranker;
                if (r == null) {
                    respond(ex, 503, "{\"error\":\"catalog not ready\"}");
                    return;
                }
                respond(ex, 200, suggestionsJson(r.searchVariants(query, List.of(), null, limit), query));
            }
        } catch (Exception e) {
            respond(ex, 400, err(e));
        }
    }

    /** GET /resolve?stereotype=&id= -> the auto-resolved UAF base concept for a stereotype. */
    private static void handleResolve(HttpExchange ex) throws IOException {
        try {
            UafConceptResolver res = resolver;
            String q = ex.getRequestURI().getQuery();
            String stereotype = param(q, "stereotype");
            String id = param(q, "id");
            if (res == null || stereotype == null) {
                respond(ex, 200, "{}");
                return;
            }
            UafConceptResolver.UafConcept c = res.resolve(stereotype, id);
            if (c == null) {
                respond(ex, 200, "{}");
                return;
            }
            respond(ex, 200, "{\"iri\":\"" + json(c.iri()) + "\",\"label\":\"" + json(c.label())
                    + "\",\"foundationalIri\":" + quoteOrNull(c.foundationalIri())
                    + ",\"foundationalLabel\":" + quoteOrNull(c.foundationalLabel()) + "}");
        } catch (Exception e) {
            respond(ex, 400, err(e));
        }
    }

    /** Body = SPARQL text; ?inference=true wraps the catalog dataset in an OWL-micro InfModel. */
    private static void handleSparql(HttpExchange ex) throws IOException {
        try {
            String queryText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Model dataset = catalogModel;
            if (dataset == null) {
                respond(ex, 503, "{\"error\":\"dataset not ready\"}");
                return;
            }
            if (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("inference=true")) {
                dataset = ModelFactory.createInfModel(ReasonerRegistry.getOWLMicroReasoner(), dataset);
            }
            Query query = QueryFactory.create(queryText);
            try (QueryExecution qe = QueryExecutionFactory.create(query, dataset)) {
                if (query.isSelectType()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ResultSetFormatter.outputAsJSON(out, qe.execSelect());
                    respond(ex, 200, out.toString(StandardCharsets.UTF_8));
                } else if (query.isAskType()) {
                    respond(ex, 200, "{\"boolean\":" + qe.execAsk() + "}");
                } else {
                    respond(ex, 400, "{\"error\":\"only SELECT and ASK supported\"}");
                }
            }
        } catch (Exception e) {
            respond(ex, 400, err(e));
        }
    }

    /** POST turtle (the project ABox) -> reasoner consistency + SHACL violations. */
    private static void handleReason(HttpExchange ex) throws IOException {
        try {
            String turtle = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SHACLValidator validator = new SHACLValidator();
            SHACLValidator.ReasonerResult reasoning = validator.runHermitReasoner(turtle, tboxFiles());
            File shapes = shapesFile();
            SHACLValidator.SHACLAuditReport audit = shapes == null
                    ? new SHACLValidator.SHACLAuditReport(0, List.of("SHACL shapes file not found."))
                    : validator.runSHACLAudit(turtle, shapes.getAbsolutePath());
            List<String> messages = new ArrayList<>(reasoning.getMessages());
            messages.addAll(audit.getMessages());
            StringBuilder sb = new StringBuilder("{\"consistent\":").append(reasoning.isConsistent())
                    .append(",\"violations\":").append(audit.getViolationsCount())
                    .append(",\"messages\":[");
            for (int i = 0; i < messages.size(); i++) {
                sb.append(i == 0 ? "" : ",").append('"').append(json(messages.get(i))).append('"');
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());
        } catch (Exception e) {
            respond(ex, 400, err(e));
        }
    }

    // --- JSON serialization ----------------------------------------------------------------

    private static String suggestionsJson(List<ConceptSuggestion> hits, String subject) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            ConceptSuggestion s = hits.get(i);
            String sbvr = SBVR.generatePlainSBVR(
                    subject == null || subject.isBlank() ? s.entry().label() : subject, s.entry().iri(), null, null);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"iri\":\"").append(json(s.entry().iri()))
                    .append("\",\"curie\":\"").append(json(s.entry().curie()))
                    .append("\",\"label\":\"").append(json(s.entry().label()))
                    .append("\",\"ontologyId\":\"").append(json(s.entry().ontologyId()))
                    .append("\",\"score\":").append(s.score())
                    .append(",\"matchedVariant\":").append(quoteOrNull(s.matchedVariant()))
                    .append(",\"context\":").append(quoteOrNull(s.context()))
                    .append(",\"sbvr\":\"").append(json(sbvr)).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String candidatesJson(List<AlignmentCandidate> hits) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            AlignmentCandidate c = hits.get(i);
            String curie = (c.oboId() != null && !c.oboId().isBlank()) ? c.oboId() : c.iri();
            String ctx = "via OLS/OntoPortal [" + (c.ontologyPrefix() == null ? "?" : c.ontologyPrefix()) + "]"
                    + (c.restrictivelyLicensed() ? "  license: " + c.licenseNote() : "")
                    + (c.description() == null || c.description().isBlank() ? "" : "  -  " + c.description());
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"iri\":\"").append(json(c.iri()))
                    .append("\",\"curie\":\"").append(json(curie))
                    .append("\",\"label\":\"").append(json(c.label() == null ? curie : c.label()))
                    .append("\",\"ontologyId\":\"OLS4:").append(json(c.ontologyPrefix() == null ? "ols4" : c.ontologyPrefix()))
                    .append("\",\"score\":0.72,\"matchedVariant\":\"ONLINE\",\"context\":\"").append(json(ctx))
                    .append("\",\"license\":").append(quoteOrNull(c.licenseNote())).append("}");
        }
        return sb.append(']').toString();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static List<File> tboxFiles() {
        List<File> files = new ArrayList<>();
        if (pluginDir != null) {
            File tbox = new File(pluginDir, "tbox");
            File[] tf = tbox.listFiles((d, n) -> n.toLowerCase().endsWith(".ttl"));
            if (tf != null) {
                java.util.Collections.addAll(files, tf);
            }
            File[] cf = new File(pluginDir, "catalog").listFiles((d, n) -> n.toLowerCase().endsWith(".ttl"));
            if (cf != null) {
                for (File f : cf) {
                    if (f.getName().contains("uaf") || f.getName().contains("gufo")) {
                        files.add(f);
                    }
                }
            }
        }
        return files;
    }

    private static File shapesFile() {
        String override = System.getProperty("semantic.plugin.shapes");
        if (override != null && new File(override).isFile()) {
            return new File(override);
        }
        if (pluginDir != null) {
            File f = new File(pluginDir, "semantic-plugin-shapes.ttl");
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return new JsonObject();
        }
        return JSON.parse(new ByteArrayInputStream(body));
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.hasKey(k) && o.get(k).isString() ? o.get(k).getAsString().value() : null;
    }

    /**
     * Parses the optional {@code context} object into a {@link ScopeContext}:
     * {@code {"uafLayer":"RESOURCE","constructKind":"STRUCTURE","terms":[{"text":"SUV","role":"OWNER"},...]}}.
     * Missing/blank -> {@link ScopeContext#EMPTY} (the ranker then behaves as the non-scoped path).
     */
    private static ScopeContext parseScope(JsonObject req) {
        if (req == null || !req.hasKey("context") || !req.get("context").isObject()) {
            return ScopeContext.EMPTY;
        }
        JsonObject c = req.get("context").getAsObject();
        String layer = str(c, "uafLayer");
        String kind = str(c, "constructKind");
        List<ScopeContext.ContextTerm> terms = new ArrayList<>();
        if (c.hasKey("terms") && c.get("terms").isArray()) {
            for (JsonValue v : c.get("terms").getAsArray()) {
                if (!v.isObject()) {
                    continue;
                }
                JsonObject t = v.getAsObject();
                String text = str(t, "text");
                if (text == null || text.isBlank()) {
                    continue;
                }
                ScopeContext.Role role = ScopeContext.Role.SIBLING;
                String roleStr = str(t, "role");
                if (roleStr != null) {
                    try {
                        role = ScopeContext.Role.valueOf(roleStr.trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        // unknown role -> default SIBLING weight
                    }
                }
                terms.add(new ScopeContext.ContextTerm(text.trim(), role));
            }
        }
        ScopeContext scope = new ScopeContext(layer, kind, terms);
        return scope.isEmpty() ? ScopeContext.EMPTY : scope;
    }

    private static List<String> strList(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o != null && o.hasKey(k) && o.get(k).isArray()) {
            for (JsonValue v : o.get(k).getAsArray()) {
                if (v.isString()) {
                    out.add(v.getAsString().value());
                }
            }
        }
        return out;
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String quoteOrNull(String s) {
        return s == null ? "null" : "\"" + json(s) + "\"";
    }

    private static String json(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String err(Exception e) {
        return "{\"error\":\"" + json(String.valueOf(e.getMessage())) + "\"}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
