# OLS4 Integration & Capability Alignment — Recommendation (2026-07-05)

Owner surfaced OLS4 (https://www.ebi.ac.uk/ols4/) and asked: use the website / its API /
its MCP, or run it locally (can we)? And: "Search is a Capability — what is Search *as a
Capability*?" (NCIT:C54117 "Search" is the activity, not the capability.)

## TL;DR

- **Use the OLS4 public REST API directly** from the plugin (keyless, no rate limit,
  Apache-2.0 code / per-ontology data licenses), behind a `TermSource` interface with a
  **configurable base URL** and a local cache.
- **For offline/regulated deployments, self-host OLS4** (Docker; the user has Docker
  Desktop) scoped to only the ontologies you need, and point the same base URL at
  localhost. **Same code, config-only switch.** This is the air-gap answer.
- **Do NOT wire the plugin to the OLS4 MCP.** MCP is for LLM hosts, not a JVM REST
  service; the EBI MCP is a thin wrapper over the same REST API. (The MCP IS useful for
  *Claude Code itself* during development, and a possible future in-plugin LLM assistant.)
- **Do NOT adopt the old PRIDE Java `ols-client`** (OLS3-era). Hand-roll ~200 lines
  against the REST API using the JVM `HttpClient` already on Cameo's classpath. Note the
  in-Cameo `groovy.json` / FastStringUtils trap — hand-roll JSON on the Groovy side.

## Facts that matter

- OLS4 public API: `https://www.ebi.ac.uk/ols4/api` — `/search?q=`, `/select?q=`
  (type-ahead), `/terms?iri=`, and `_links` ancestors/descendants for narrowing.
  ~280 ontologies / ~8.7M classes: NCIT, BFO, DRON (drug), OBI, EFO, OGMS.
- **Trap: OLS4's `cco` is the *Cell Cycle Ontology*, NOT the Common Core Ontologies.**
  CCO (the BFO-based, DoD/Army-aligned suite most relevant to UAF) is **not in OLS4** —
  vendor it from its GitHub TTL or load it into a self-hosted instance.
- OLS4 is **life-science-centric**: it does NOT index medical-device/regulatory
  vocabularies (IMDRF, GMDN, IEC 62304, ISO 14971). Those remain owner-imported via the
  existing on-demand catalog. **OLS4 is a supplement, not the whole answer.**
- Self-host: Apache-2.0, `github.com/EBISPOT/ols4`. Dev branch = single Postgres+pgvector
  (simplest); stable = Solr 9 + Neo4j 4.4. Load only chosen ontologies via one config.
  BFO+CCO+gUFO+small NCIT slice ≈ single-digit GB RAM; full NCIT ≈ 8–12 GB. Ship the
  pre-loaded DB volume, not the ingest step. License gate: NCIT ~public-domain;
  SNOMED/MedDRA/LOINC restrictive — clear before baking into a shipped volume.

## "Search as a Capability" — the concrete answer

A Capability is a **realizable entity / disposition**, not the process it realizes.
Mapping UAF Capability "Search" onto NCIT:C54117 (an Activity) is a category error. Use a
**two-slot alignment record** joined by a `realizes` edge:

| Slot | Holds | Target for "Search" |
|---|---|---|
| 1 — Capability (realizable) | the disposition/capability universal | **cco:Agent Capability** (`…/ont00001379`), or gUFO-native **gufo:IntrinsicMode** (tighten from the current coarse `gufo:Aspect`; never `gufo:Quality`) |
| 2 — Realized activity (occurrent) | the process it realizes | **NCIT:C54117 "Search"** (`obo:NCIT_C54117`, Semantic_Type=Activity) |
| edge 1→2 | realizes | **BFO:realized in** (`BFO_0000054`, inverse `BFO_0000055`) or **gufo:manifestedIn** |

- CCO is the strongest anchor (BFO-based, same DoD/UAF/IDEAS community) and brings
  `has capability`/`capability of` to bind the Capability to its bearer (Operational
  Performer / Resource).
- Store the alignment against the capability **class**, not a hard-coded `Disposition`
  parent — released CCO puts Agent Capability under BFO Realizable Entity while the
  Beverley/Merrell/Smith "Capabilities: An Ontology" paper moves it under Disposition;
  binding to the class stays correct across versions.
- **Validation rule (surface in the UI):** Slot-1 target must be under
  realizable/disposition/aspect (`BFO_0000017`/`BFO_0000016`/`gufo:IntrinsicMode`);
  Slot-2 under process/activity/event. **Flag** a Capability aligned to an Activity term
  in Slot 1 — detect via the term's `Semantic_Type` (from OLS4 lookup) or a BFO-ancestor
  walk. This kills the "top hit for Search is an Activity" mistake automatically.

## Integration design (reuses existing infra)

`TermSource` port in the `align` package: `Ols4TermSource` (base URL injected),
`LocalTtlTermSource` (vendored BFO/CCO/gUFO fragments = offline floor),
`CachingTermSource` (disk cache + the not-empty invariant). On commit, `lookup()` the
canonical record → persist IRI + obo_id + prefix + **ontology versionInfo** + source
base-URL on the element (regulated traceability) → write into the on-demand catalog →
trigger the existing `/catalog/reload` → assert local re-resolution. OLS4 becomes one
*feeder* into the on-demand catalog, beside the owner's imported device/regulatory
ontologies. Re-resolution never depends on a live OLS call.

## Phased plan (each phase ends with the IT suite green)

- P0 Recon+seam: grep the plugin UAF TTL for the current Capability↔gUFO axiom;
  `TermSource` interface + `AlignmentCandidate`.
- P1 Public REST search wired into the align action; test aligns "Search", asserts
  NCIT:C54117 with an **Activity badge**.
- P2 Two-slot capability record + realizes edge + Slot-1-realizable validation; vendor
  offline TTL fragments.
- P3 Catalog import + provenance (versionInfo); re-resolve offline.
- P4 Self-hosted OLS4 (dev Postgres+pgvector) with a curated ontology set; run P1–P3
  suite with outbound network blocked = air-gapped proof.
- P5 Considerate-use hardening (debounce, User-Agent, pin the OLS4 commit).

## Decisions for the owner

1. **Self-hosted OLS4 volume vs. in-JVM OWL (Jena) for the offline path?** Recommend
   local OLS4 for the search/hierarchy UX; in-JVM OWL only for the tiny BFO/CCO/gUFO
   capability fragments as the floor.
2. **Canonical serialization of the realizes edge** (neutral internal predicate + both
   BFO and gUFO IRIs, vs pick one)? Recommend neutral + both; owner owns the persisted
   audit format.
3. **CCO sourcing + ontology licenses** — approve vendoring CCO's `AgentOntology.ttl`
   (not in OLS4) and clear per-ontology redistribution licenses before any pre-loaded
   volume ships. Legal gate, not technical.
