# Semantic Catalog Service — Architecture & Install (2026-07-07)

Owner constraint: **avoid a large memory impact inside Cameo.** As we federate more ontologies
(CCO, QUDT, IMCE, governance, …) the in-Cameo catalog grew to ~8,000 concepts / ~120K TBox
triples and pushed startup from 12s → 42s. In-JVM does not scale in the direction we're going.

## The design

Move the heavy ontology work **out of Cameo** into a standalone **Semantic Catalog Service**
(a separate JVM). Cameo becomes a thin client.

```
  Cameo JVM (plugin, thin)                 Semantic Catalog Service (separate JVM)
  ─────────────────────────                ───────────────────────────────────────
  • model access (EDT)          REST        • catalog index (~8k concepts)
  • sidebar UI              ───────────►     • union TBox model (~120k triples)
  • CatalogServiceClient    ◄───────────     • SuggestionRanker + QueryVariants
  • RDF export (project ABox only)           • UAF resolver, SPARQL, reasoner/SHACL
  • auto-start / stop the service            • online sources (OLS4 / TIB / OntoPortal)
```

The ontology heap (hundreds of MB) lives in the **service**, not Cameo. Cameo's heap stays flat
regardless of how many ontologies we add. The service also enables independent reload/scale and
a shared team deployment.

**Status:** the service + client + auto-start launcher are built and **validated standalone**
(no Cameo): `/health` (8,268 concepts, 120K triples), `/suggest` (uaf:Challenge base + SBVR),
`/search` (local QUDT `Force`; online TIB `gearbox`), `/resolve`, `/sparql` (3,024 owl:Class);
`CatalogServiceClientTest` passes against the running service. The plugin wiring (sidebar/audit/
ontology-view → client, remove the in-JVM catalog) is the remaining step, to be verified live in
Cameo.

## Endpoints (localhost REST, JSON)

| Endpoint | Method | Purpose |
|---|---|---|
| `/health` | GET | `{ready, tboxTriples, onlineSources}` |
| `/suggest` | POST | element-driven suggestions `{name, stereotypes[], narrowFrom?, limit}` |
| `/search` | POST | typed search `{query, ontologyFilter?, limit, online?}` (online = OLS/TIB/OntoPortal) |
| `/resolve` | GET | `?stereotype=&id=` → auto-resolved UAF base concept |
| `/sparql` | POST | SPARQL SELECT/ASK over the catalog (`?inference=true` for OWL-micro) |
| `/reason` | POST | reason over posted project turtle vs catalog TBox + SHACL |
| `/catalog/reload` | POST | re-index shipped + user catalogs |

## Running it

### Local (default — the plugin auto-starts it)

The plugin launches the service as a **local child JVM** at Cameo start and stops it on close
(the child PID is journaled to `~/.semantic_alignment_plugin/catalog-service.pid.json`; its log
is `catalog-service.log`). Nothing to configure. Tuning:

- `-Dsemantic.plugin.service.port=8767` — the local port (default 8767).
- `-Dsemantic.plugin.service.heap=1g` — the service JVM heap (default 1g; the ontology memory
  lives here, so raise it — not Cameo's — if you load very large ontologies).

### Shared server (one service for a team)

Run the service jar on a host and point every modeler's plugin at it:

```bash
# On the server (JDK 21+). Copy the deployed plugin's service artifacts, or build them:
#   gradle serviceJar   →   build/service/semantic-catalog-service.jar  +  build/service/lib/
java -Xmx4g \
  -cp "semantic-catalog-service.jar:lib/*" \
  com.nomagic.magicdraw.plugins.semantic.service.SemanticCatalogService \
  --port 8767 --plugin-dir /path/to/deployed/plugin        # (holds catalog/, tbox/, shapes)
```

Then in Cameo (JVM option or the plugin config):

```
-Dsemantic.plugin.service.url=http://<server-host>:8767
```

When `service.url` is set, the plugin connects to that shared service and does **not** auto-start
a local one. Bind the server to a trusted network (the REST surface is unauthenticated, same
trust model as the local harness); front it with TLS/auth if exposed beyond localhost.

### Memory sizing (server)

- BFO+CCO+QUDT+PROV/ODRL/DPV+IMCE (the shipped breadth bundle): ~1–1.5 GB heap is comfortable.
- Add restrictive/large ontologies (full NCIT, SNOMED via a user's license): 2–4 GB.
- One shared 4 GB service serves a whole team; each Cameo stays light.

## Config summary

| Property | Default | Meaning |
|---|---|---|
| `semantic.plugin.service.url` | *(unset)* | connect to a shared server; disables local auto-start |
| `semantic.plugin.service.port` | 8767 | local child-JVM port |
| `semantic.plugin.service.heap` | 1g | local child-JVM heap |
| `semantic.plugin.catalog` | *(plugin)/catalog* | catalog dir override |
| `semantic.plugin.ols.endpoints` | EBI OLS4 + TIB | online OLS-family endpoints |
| `semantic.plugin.ontoportal.<portal>.apikey` | *(unset)* | enable an OntoPortal (see ontoportal_setup.md) |
