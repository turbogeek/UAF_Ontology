package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.UxMetrics;
import com.nomagic.magicdraw.plugins.semantic.rest.SemanticRestService;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the plugin-owned REST surface against an in-memory dataset: /sparql
 * SELECT + ASK + error paths, /metrics, /catalog/reload. Runs on an ephemeral test
 * port so parallel builds cannot collide with a live Cameo on 8766.
 * Trace: v3 plan sections 3 and 5
 */
public class SemanticRestServiceTest {

    private static final int PORT = 18766;
    private SemanticRestService service;

    @Before
    public void start() {
        System.setProperty("semantic.plugin.rest.port", String.valueOf(PORT));
        Model dataset = ModelFactory.createDefaultModel();
        RDFDataMgr.read(dataset, new ByteArrayInputStream("""
                @prefix ex:   <http://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:echo a ex:Base ; rdfs:label "EchoBase" .
                """.getBytes(StandardCharsets.UTF_8)), Lang.TTL);
        service = new SemanticRestService(() -> dataset, () -> "{\"concepts\":42}");
        service.start();
    }

    @After
    public void stop() {
        service.stop();
        System.clearProperty("semantic.plugin.rest.port");
    }

    private int[] statusAndBody(String method, String path, String body, StringBuilder out)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path)
                .openConnection();
        conn.setRequestMethod(method);
        if (body != null) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        var stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            out.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        return new int[]{code};
    }

    @Test
    public void testSelectAndAskAndErrors() throws Exception {
        StringBuilder body = new StringBuilder();
        assertEquals(200, statusAndBody("POST", "/sparql",
                "SELECT ?s WHERE { ?s a <http://example.org/Base> }", body)[0]);
        assertTrue("bindings must include the individual: " + body,
                body.toString().contains("http://example.org/echo"));

        body.setLength(0);
        statusAndBody("POST", "/sparql", "ASK { ?s a <http://example.org/Base> }", body);
        assertTrue(body.toString().contains("true"));

        body.setLength(0);
        assertEquals("malformed query must be a client error, not a crash",
                400, statusAndBody("POST", "/sparql", "NOT SPARQL AT ALL", body)[0]);

        body.setLength(0);
        assertEquals(400, statusAndBody("POST", "/sparql", "", body)[0]);
    }

    @Test
    public void testMetricsAndCatalogReload() throws Exception {
        UxMetrics.reset();
        StringBuilder body = new StringBuilder();
        assertEquals(200, statusAndBody("GET", "/metrics", null, body)[0]);
        assertTrue(body.toString().contains("\"clicks\":0"));

        body.setLength(0);
        assertEquals(200, statusAndBody("POST", "/catalog/reload", "", body)[0]);
        assertEquals("{\"concepts\":42}", body.toString());

        body.setLength(0);
        assertEquals(200, statusAndBody("GET", "/health", null, body)[0]);
        assertTrue(body.toString().contains("/catalog/reload"));
    }
}
