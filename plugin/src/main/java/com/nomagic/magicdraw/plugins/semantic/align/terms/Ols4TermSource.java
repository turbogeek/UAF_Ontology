package com.nomagic.magicdraw.plugins.semantic.align.terms;

import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.log4j.Logger;

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

/**
 * TermSource backed by an OLS4 REST endpoint. The base URL is injectable
 * (-Dsemantic.plugin.ols4.baseurl, default the EBI public API) so the SAME code serves
 * the connected public API and a self-hosted/air-gapped OLS4 - the owner's remote-first,
 * config-only-switch design.
 *
 * JSON is parsed with Jena's bundled org.apache.jena.atlas.json (NOT groovy.json, which
 * breaks inside Cameo via the FastStringUtils SPI). Network/parse failures degrade to an
 * empty result and a journal line - never an exception into the UI.
 * Trace: OLS4 integration recommendation, P1
 */
public final class Ols4TermSource implements TermSource {

    private static final Logger log = Logger.getLogger(Ols4TermSource.class);
    private static final String BASE_URL_PROPERTY = "semantic.plugin.ols4.baseurl";
    private static final String DEFAULT_BASE = "https://www.ebi.ac.uk/ols4/api";
    private static final String USER_AGENT =
            "CameoSemanticAlignmentPlugin/3 (UAF ontology alignment; contact via plugin)";

    private final String baseUrl;
    private final HttpClient http;

    public Ols4TermSource() {
        this(System.getProperty(BASE_URL_PROPERTY, DEFAULT_BASE));
    }

    public Ols4TermSource(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return "ols4:" + baseUrl;
    }

    @Override
    public List<AlignmentCandidate> search(String query, String ontologyFilter, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/search?q=").append(enc(query))
                .append("&rows=").append(Math.max(1, limit))
                .append("&type=class");
        if (ontologyFilter != null && !ontologyFilter.isBlank()) {
            url.append("&ontology=").append(enc(ontologyFilter));
        }
        String body = get(url.toString());
        if (body == null) {
            return List.of();
        }
        try {
            List<AlignmentCandidate> results = parseSearchResponse(body);
            DiagnosticLog.event("TERMSOURCE", "OLS4 search '" + query + "'"
                    + (ontologyFilter == null ? "" : " [" + ontologyFilter + "]")
                    + " -> " + results.size() + " candidates");
            return results;
        } catch (Exception e) {
            log.error("OLS4 search parse failed", e);
            DiagnosticLog.event("ERROR", "OLS4 search parse failed: " + e);
            return List.of();
        }
    }

    @Override
    public Optional<AlignmentCandidate> lookup(String iri) {
        if (iri == null || iri.isBlank()) {
            return Optional.empty();
        }
        String body = get(baseUrl + "/terms?iri=" + enc(iri));
        if (body == null) {
            return Optional.empty();
        }
        try {
            return parseTermLookup(body);
        } catch (Exception e) {
            log.error("OLS4 lookup parse failed", e);
            DiagnosticLog.event("ERROR", "OLS4 lookup parse failed: " + e);
            return Optional.empty();
        }
    }

    // --- parsing (static + pure, so it unit-tests against captured fixtures) ----------

    /** Parses an OLS4 /search response body into candidates. Never throws. */
    public static List<AlignmentCandidate> parseSearchResponse(String json) {
        List<AlignmentCandidate> out = new ArrayList<>();
        JsonObject root = parseOrNull(json);
        if (root == null) {
            return out;
        }
        JsonObject response = getObject(root, "response");
        if (response == null) {
            return out;
        }
        JsonArray docs = getArray(response, "docs");
        if (docs == null) {
            return out;
        }
        for (JsonValue value : docs) {
            if (!value.isObject()) {
                continue;
            }
            JsonObject doc = value.getAsObject();
            // ontology_name is the canonical lowercase id used for domain filtering AND
            // license keys (ontology_prefix is the display form, e.g. "NCIT").
            String ontologyName = str(doc, "ontology_name");
            String prefixKey = ontologyName != null ? ontologyName : str(doc, "ontology_prefix");
            out.add(new AlignmentCandidate(
                    str(doc, "iri"),
                    str(doc, "label"),
                    str(doc, "obo_id"),
                    prefixKey,
                    firstOf(doc, "description"),
                    str(doc, "type"),
                    "",
                    0.0,
                    OntologyLicenses.noteFor(prefixKey)));
        }
        return out;
    }

    /** Parses an OLS4 /terms lookup response, including the Semantic_Type. Never throws. */
    public static Optional<AlignmentCandidate> parseTermLookup(String json) {
        JsonObject root = parseOrNull(json);
        if (root == null) {
            return Optional.empty();
        }
        JsonObject embedded = getObject(root, "_embedded");
        JsonObject term = null;
        if (embedded != null) {
            JsonArray terms = getArray(embedded, "terms");
            if (terms != null && !terms.isEmpty() && terms.get(0).isObject()) {
                term = terms.get(0).getAsObject();
            }
        } else if (root.hasKey("iri")) {
            term = root; // some deployments return the term directly
        }
        if (term == null) {
            return Optional.empty();
        }
        String ontologyName = str(term, "ontology_name");
        String prefixKey = ontologyName != null ? ontologyName : str(term, "ontology_prefix");
        String semanticType = "";
        JsonObject annotation = getObject(term, "annotation");
        if (annotation != null && annotation.hasKey("Semantic_Type")) {
            JsonValue st = annotation.get("Semantic_Type");
            semanticType = st.isArray() && !st.getAsArray().isEmpty()
                    ? st.getAsArray().get(0).getAsString().value()
                    : (st.isString() ? st.getAsString().value() : "");
        }
        return Optional.of(new AlignmentCandidate(
                str(term, "iri"),
                str(term, "label"),
                str(term, "obo_id"),
                prefixKey,
                firstOf(term, "description"),
                str(term, "type") == null ? "class" : str(term, "type"),
                semanticType,
                0.0,
                OntologyLicenses.noteFor(prefixKey)));
    }

    // --- helpers ----------------------------------------------------------------------

    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                DiagnosticLog.event("ERROR", "OLS4 HTTP " + response.statusCode() + " for " + url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.error("OLS4 request failed: " + url, e);
            DiagnosticLog.event("ERROR", "OLS4 request failed (" + e.getClass().getSimpleName()
                    + "): " + url);
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Lenient parse: malformed JSON yields null rather than a thrown exception. */
    private static JsonObject parseOrNull(String json) {
        try {
            return JSON.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("OLS4 JSON parse failed (returning empty): " + e.getMessage());
            return null;
        }
    }

    private static JsonObject getObject(JsonObject parent, String key) {
        if (parent.hasKey(key) && parent.get(key).isObject()) {
            return parent.get(key).getAsObject();
        }
        return null;
    }

    private static JsonArray getArray(JsonObject parent, String key) {
        if (parent.hasKey(key) && parent.get(key).isArray()) {
            return parent.get(key).getAsArray();
        }
        return null;
    }

    private static String str(JsonObject obj, String key) {
        if (obj.hasKey(key) && obj.get(key).isString()) {
            return obj.get(key).getAsString().value();
        }
        return null;
    }

    /** First element of a string-or-array field like OLS4 'description'. */
    private static String firstOf(JsonObject obj, String key) {
        if (!obj.hasKey(key)) {
            return "";
        }
        JsonValue value = obj.get(key);
        if (value.isString()) {
            return value.getAsString().value();
        }
        if (value.isArray() && !value.getAsArray().isEmpty() && value.getAsArray().get(0).isString()) {
            return value.getAsArray().get(0).getAsString().value();
        }
        return "";
    }
}
