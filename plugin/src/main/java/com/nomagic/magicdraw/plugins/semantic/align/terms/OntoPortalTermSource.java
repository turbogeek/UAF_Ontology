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
 * TermSource backed by an OntoPortal deployment (BioPortal, IndustryPortal, MatPortal,
 * AgroPortal, EcoPortal, …). Every OntoPortal instance exposes the same REST contract, so
 * ONE parameterized class covers them all — just register instances with different
 * (baseUrl, apiKey). Request: {@code GET {base}/search?q=&apikey=&include=prefLabel,synonym,
 * definition&pagesize=}. Response: a JSON-LD {@code collection[]} of hits carrying
 * {@code @id} (class IRI), {@code prefLabel}, {@code synonym[]}, {@code definition[]}, and
 * {@code links.ontology} (a URL whose last path segment is the ontology acronym → source).
 *
 * The adapter reads the source acronym and consults {@link OntologyLicenses} so restrictively
 * licensed ontologies (SNOMED/MedDRA/UMLS-derived) are FLAGGED per the notify-on-restricted
 * policy. JSON is parsed with Jena's bundled atlas.json (the groovy.json/FastStringUtils trap
 * does not apply here, but we stay consistent with Ols4TermSource). Network/parse failures
 * degrade to an empty result + a journal line — never an exception into the UI.
 * Trace: design/ontology_sources.md (OntoPortal generic adapter)
 */
public final class OntoPortalTermSource implements TermSource {

    private static final Logger log = Logger.getLogger(OntoPortalTermSource.class);
    private static final String USER_AGENT =
            "CameoSemanticAlignmentPlugin/3 (UAF ontology alignment; contact via plugin)";

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultOntologies; // comma-separated acronyms, or null
    private final HttpClient http;

    public OntoPortalTermSource(String name, String baseUrl, String apiKey, String defaultOntologies) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultOntologies = (defaultOntologies == null || defaultOntologies.isBlank())
                ? null : defaultOntologies;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Consistent with Ols4TermSource: pin HTTP/1.1 (Java's default HTTP/2 handshake
                // dies behind this network's firewall on some hosts).
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String id() {
        return "ontoportal:" + name;
    }

    @Override
    public List<AlignmentCandidate> search(String query, String ontologyFilter, int limit) {
        if (query == null || query.isBlank() || !isConfigured()) {
            return List.of();
        }
        String scope = (ontologyFilter != null && !ontologyFilter.isBlank())
                ? ontologyFilter : defaultOntologies;
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/search?q=").append(enc(query))
                .append("&pagesize=").append(Math.max(1, limit))
                .append("&include=prefLabel,synonym,definition")
                .append("&apikey=").append(enc(apiKey));
        if (scope != null) {
            url.append("&ontologies=").append(enc(scope));
        }
        String body = get(url.toString());
        if (body == null) {
            return List.of();
        }
        try {
            List<AlignmentCandidate> results = parseSearchResponse(body);
            DiagnosticLog.event("TERMSOURCE", "OntoPortal[" + name + "] '" + query + "' -> "
                    + results.size() + " candidates");
            return results;
        } catch (Exception e) {
            log.error("OntoPortal[" + name + "] parse failed", e);
            DiagnosticLog.event("ERROR", "OntoPortal[" + name + "] parse failed: " + e);
            return List.of();
        }
    }

    @Override
    public Optional<AlignmentCandidate> lookup(String iri) {
        // OntoPortal class lookup needs the ontology acronym in the path; the plain search
        // does not carry a semantic type, so a lookup adds little here. Return empty (the
        // capability guard simply won't fire for OntoPortal terms). Kept simple by design.
        return Optional.empty();
    }

    // --- parsing (static + pure, so it unit-tests against captured fixtures) ---------------

    /** Parses an OntoPortal /search response body into candidates. Never throws. */
    public static List<AlignmentCandidate> parseSearchResponse(String json) {
        List<AlignmentCandidate> out = new ArrayList<>();
        JsonObject root = parseOrNull(json);
        if (root == null) {
            return out;
        }
        JsonArray collection = getArray(root, "collection");
        if (collection == null) {
            return out;
        }
        for (JsonValue value : collection) {
            if (!value.isObject()) {
                continue;
            }
            JsonObject hit = value.getAsObject();
            String iri = str(hit, "@id");
            if (iri == null || iri.isBlank()) {
                continue;
            }
            String label = str(hit, "prefLabel");
            String acronym = ontologyAcronym(hit);
            String prefixKey = acronym == null ? "" : acronym;
            String description = firstOfArray(hit, "definition");
            String type = classifyType(str(hit, "@type"));
            out.add(new AlignmentCandidate(
                    iri,
                    label == null ? localName(iri) : label,
                    acronym == null ? "" : acronym + ":" + localName(iri),
                    prefixKey,
                    description,
                    type,
                    "",
                    0.0,
                    OntologyLicenses.noteFor(prefixKey)));
        }
        return out;
    }

    /** links.ontology is e.g. "https://data.bioontology.org/ontologies/SNOMEDCT" -> "SNOMEDCT". */
    private static String ontologyAcronym(JsonObject hit) {
        if (hit.hasKey("links") && hit.get("links").isObject()) {
            String ontologyUrl = str(hit.get("links").getAsObject(), "ontology");
            if (ontologyUrl != null && !ontologyUrl.isBlank()) {
                return lastPathSegment(ontologyUrl);
            }
        }
        // Some deployments echo an "ontology_acronym" field directly.
        String direct = str(hit, "ontology_acronym");
        return direct != null ? direct : null;
    }

    private static String classifyType(String atType) {
        if (atType == null) {
            return "class";
        }
        String lower = atType.toLowerCase();
        if (lower.contains("property")) {
            return "property";
        }
        if (lower.contains("individual") || lower.contains("namedindividual")) {
            return "individual";
        }
        return "class";
    }

    // --- helpers --------------------------------------------------------------------------

    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                DiagnosticLog.event("ERROR", "OntoPortal[" + name + "] HTTP " + response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.error("OntoPortal[" + name + "] request failed", e);
            DiagnosticLog.event("ERROR", "OntoPortal[" + name + "] request failed ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String lastPathSegment(String url) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    private static JsonObject parseOrNull(String json) {
        try {
            return JSON.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("OntoPortal JSON parse failed (returning empty): " + e.getMessage());
            return null;
        }
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

    /** First element of a string-or-array field like OntoPortal 'definition'. */
    private static String firstOfArray(JsonObject obj, String key) {
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
