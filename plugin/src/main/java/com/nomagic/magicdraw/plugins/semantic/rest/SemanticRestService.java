package com.nomagic.magicdraw.plugins.semantic.rest;

import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;
import com.nomagic.magicdraw.plugins.semantic.UxMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Small plugin-owned REST surface on port 8766 (separate from the script harness on
 * 8765): /sparql lets scenario tests and future LLM assistants query the semantic
 * dataset headlessly; /metrics + /metrics/reset expose the UX click budgets.
 * Localhost-only, no auth (same trust model as the harness). No System.exit anywhere.
 * Trace: v3 plan sections 3 and 5
 */
public final class SemanticRestService {

    private static final Logger log = Logger.getLogger(SemanticRestService.class);
    private static final String PORT_PROPERTY = "semantic.plugin.rest.port";

    private final Supplier<Model> datasetSupplier;
    private HttpServer server;

    /**
     * @param datasetSupplier supplies the query dataset (catalog TBox + project ABox);
     *                        invoked per request so results always reflect the live model
     */
    public SemanticRestService(Supplier<Model> datasetSupplier) {
        this.datasetSupplier = datasetSupplier;
    }

    public void start() {
        int port = Integer.parseInt(System.getProperty(PORT_PROPERTY, "8766"));
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            server.createContext("/health", ex -> respond(ex, 200, "application/json",
                    "{\"service\":\"semantic-alignment\",\"endpoints\":[\"/sparql\",\"/metrics\",\"/metrics/reset\"]}"));
            server.createContext("/metrics", ex -> {
                if ("POST".equals(ex.getRequestMethod()) && ex.getRequestURI().getPath().endsWith("/reset")) {
                    UxMetrics.reset();
                }
                respond(ex, 200, "application/json", UxMetrics.toJson());
            });
            server.createContext("/metrics/reset", ex -> {
                UxMetrics.reset();
                respond(ex, 200, "application/json", UxMetrics.toJson());
            });
            server.createContext("/sparql", this::handleSparql);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "semantic-rest");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            DiagnosticLog.event("REST", "Semantic REST service on http://127.0.0.1:" + port
                    + " (/sparql /metrics /metrics/reset)");
        } catch (IOException e) {
            log.error("Semantic REST service failed to start", e);
            DiagnosticLog.event("ERROR", "Semantic REST service failed to start: " + e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Body = raw SPARQL text; ?inference=true wraps the dataset in an OWL-micro InfModel. */
    private void handleSparql(HttpExchange exchange) throws IOException {
        try {
            String queryText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (queryText.isBlank()) {
                respond(exchange, 400, "application/json", "{\"error\":\"empty query body\"}");
                return;
            }
            boolean inference = exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("inference=true");
            Model dataset = datasetSupplier.get();
            if (dataset == null) {
                respond(exchange, 503, "application/json", "{\"error\":\"dataset not ready\"}");
                return;
            }
            if (inference) {
                // OWL-micro: subclass/subproperty/domain-range at interactive cost
                InfModel inf = ModelFactory.createInfModel(ReasonerRegistry.getOWLMicroReasoner(), dataset);
                dataset = inf;
            }
            Query query = QueryFactory.create(queryText);
            try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
                if (query.isSelectType()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ResultSetFormatter.outputAsJSON(out, execution.execSelect());
                    respond(exchange, 200, "application/sparql-results+json",
                            out.toString(StandardCharsets.UTF_8));
                } else if (query.isAskType()) {
                    respond(exchange, 200, "application/json",
                            "{\"boolean\":" + execution.execAsk() + "}");
                } else {
                    respond(exchange, 400, "application/json",
                            "{\"error\":\"only SELECT and ASK are supported\"}");
                }
            }
        } catch (Exception e) {
            log.error("/sparql failed", e);
            respond(exchange, 400, "application/json",
                    "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
