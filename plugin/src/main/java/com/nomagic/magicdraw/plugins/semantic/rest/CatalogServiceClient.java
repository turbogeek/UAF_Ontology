package com.nomagic.magicdraw.plugins.semantic.rest;

import com.nomagic.magicdraw.plugins.semantic.align.ConceptEntry;
import com.nomagic.magicdraw.plugins.semantic.align.ConceptSuggestion;
import com.nomagic.magicdraw.plugins.semantic.align.UafConceptResolver;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Thin REST client the Cameo plugin uses to talk to the out-of-process Semantic Catalog
 * Service (localhost by default, or a shared server via -Dsemantic.plugin.service.url). Keeps
 * the heavy Jena models out of Cameo's JVM. All methods degrade to an empty/absent result on
 * any error so the UI never sees an exception; callers may then fall back to an in-JVM path.
 * Trace: design/service_architecture.md
 */
public final class CatalogServiceClient {

    private final String baseUrl;
    private final HttpClient http;

    public CatalogServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** True when the service answers /health with ready=true. */
    public boolean isReady() {
        String body = get("/health", 3);
        return body != null && body.contains("\"ready\":true");
    }

    /** Element-driven suggestions (scope-aware, from the service catalog). */
    public List<ConceptSuggestion> suggest(String name, List<String> stereotypes,
            String narrowFrom, int limit) {
        StringBuilder req = new StringBuilder("{\"name\":").append(q(name))
                .append(",\"stereotypes\":[");
        for (int i = 0; stereotypes != null && i < stereotypes.size(); i++) {
            req.append(i == 0 ? "" : ",").append(q(stereotypes.get(i)));
        }
        req.append("],\"narrowFrom\":").append(narrowFrom == null ? "null" : q(narrowFrom))
                .append(",\"limit\":").append(limit).append('}');
        return suggestionsFrom(post("/suggest", req.toString(), 15));
    }

    /** Typed local search, or online (OLS/TIB/OntoPortal) when online=true. */
    public List<ConceptSuggestion> search(String query, String ontologyFilter, int limit, boolean online) {
        String req = "{\"query\":" + q(query)
                + ",\"ontologyFilter\":" + (ontologyFilter == null ? "null" : q(ontologyFilter))
                + ",\"limit\":" + limit + ",\"online\":" + online + "}";
        return suggestionsFrom(post("/search", req, online ? 25 : 15));
    }

    /** The auto-resolved UAF base concept for a stereotype (name + stable id). */
    public Optional<UafConceptResolver.UafConcept> resolve(String stereotype, String id) {
        if (stereotype == null) {
            return Optional.empty();
        }
        String body = get("/resolve?stereotype=" + enc(stereotype) + (id == null ? "" : "&id=" + enc(id)), 8);
        JsonObject o = parse(body);
        if (o == null || !o.hasKey("iri")) {
            return Optional.empty();
        }
        return Optional.of(new UafConceptResolver.UafConcept(
                str(o, "iri"), str(o, "label"), "",
                str(o, "foundationalIri"), str(o, "foundationalLabel")));
    }

    /** Reason over the posted project turtle against the catalog TBox + SHACL. */
    public ReasonResult reason(String turtle) {
        String body = post("/reason", turtle, 60);
        JsonObject o = parse(body);
        if (o == null) {
            return new ReasonResult(null, 0, List.of("Semantic Catalog Service unavailable for audit."));
        }
        List<String> messages = new ArrayList<>();
        if (o.hasKey("messages") && o.get("messages").isArray()) {
            for (JsonValue v : o.get("messages").getAsArray()) {
                if (v.isString()) {
                    messages.add(v.getAsString().value());
                }
            }
        }
        Boolean consistent = o.hasKey("consistent") && o.get("consistent").isBoolean()
                ? o.get("consistent").getAsBoolean().value() : null;
        int violations = o.hasKey("violations") && o.get("violations").isNumber()
                ? (int) o.get("violations").getAsNumber().value().doubleValue() : 0;
        return new ReasonResult(consistent, violations, messages);
    }

    /** SPARQL SELECT/ASK against the catalog dataset (returns raw results JSON, or null). */
    public String sparql(String query, boolean inference) {
        return post("/sparql" + (inference ? "?inference=true" : ""), query, 30);
    }

    public String reloadCatalog() {
        return post("/catalog/reload", "", 60);
    }

    public record ReasonResult(Boolean consistent, int violations, List<String> messages) {
    }

    // --- JSON -> ConceptSuggestion ---------------------------------------------------------

    private static List<ConceptSuggestion> suggestionsFrom(String body) {
        List<ConceptSuggestion> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        try {
            JsonArray arr = JSON.parseAny(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                    .getAsArray();
            for (JsonValue v : arr) {
                if (!v.isObject()) {
                    continue;
                }
                JsonObject o = v.getAsObject();
                String context = str(o, "context");
                ConceptEntry entry = new ConceptEntry(
                        str(o, "iri"), str(o, "curie"), str(o, "label"),
                        List.of(), context == null ? "" : context,
                        str(o, "ontologyId"), prefixOf(str(o, "curie")), Set.of());
                double score = o.hasKey("score") && o.get("score").isNumber()
                        ? o.get("score").getAsNumber().value().doubleValue() : 0.0;
                out.add(new ConceptSuggestion(entry, score, str(o, "matchedVariant"), context, str(o, "sbvr")));
            }
        } catch (Exception ignored) {
            // malformed -> empty
        }
        return out;
    }

    private static String prefixOf(String curie) {
        if (curie == null) {
            return "";
        }
        int c = curie.indexOf(':');
        return c > 0 ? curie.substring(0, c) : curie;
    }

    // --- HTTP + helpers --------------------------------------------------------------------

    private String get(String path, int timeoutSec) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSec)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() < 400 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String post(String path, String body, int timeoutSec) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() < 400 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject parse(String body) {
        if (body == null) {
            return null;
        }
        try {
            return JSON.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.hasKey(k) && o.get(k).isString() ? o.get(k).getAsString().value() : null;
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
