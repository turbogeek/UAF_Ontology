# OLS4 Integration & Capability Alignment — Recommendation (2026-07-05)

> **Use cases:** see [use_cases.md](use_cases.md) §3 for what this feature is *for*.
>
> **Owner refinements (2026-07-06):**
> - **OLS4 is one of *many* sources.** The near-term goal is **breadth of domains** to map
>   models against; federate OLS4 with the user's imported ontologies and local fragments.
> - **Survey the ontology landscape** (creators & hosts) so we know where to reach for
>   breadth — see [use_cases.md](use_cases.md) §3.1 (OBO/BioPortal, BFO/gUFO/CCO, IOF/EMMO,
>   W3C/OMG/QUDT, device/regulatory).
> - **New use case: extend or correct an existing ontology** — capture proposed additions as
>   a **local overlay** (never mutate upstream), validated across **several use-case models**
>   and by the reasoner before proposing upstream.

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

## Decisions — RESOLVED with owner (2026-07-05)

1. Offline path: local OLS4 for search/hierarchy UX + in-JVM OWL for the tiny fragments,
   **reshaped by the loading & licensing policy below (remote-first).**
2. Realizes-edge serialization: **neutral internal predicate + both BFO and gUFO IRIs;**
   owner owns the persisted audit format.
3. CCO + licenses: **do NOT redistribute; notify the user on licensing issues instead.**

## Owner loading & licensing policy — REMOTE-FIRST, NEVER REDISTRIBUTE

Decisive principle: **reference, don't host.** Querying a remote service (OLS4 public
API, or an ontology's own host) is fine — not redistribution. Hosting / baking an
already-hosted ontology into our shipped artifacts or git is where licensing gets
"weird," so we avoid it.

- **Cast a wide net remotely.** OLS4's public API already indexes ~280 ontologies /
  8.7M classes — that IS the wide net, no hosting. Search across all; **filter by
  domain** (ontology scope). Covers "load many more ontologies to be useful" without
  hosting anything.
- **Never commit ontologies to git** (license exposure + repo bloat).
- **On-demand, build-time loading** for the self-hosted/air-gapped instance: a build
  process fetches the chosen set from canonical sources at load time; the repo stores
  only the *config* (list of ontology URLs), never the data. Never host an ontology
  already hosted elsewhere.
- **General users get specific, on-demand loading** — pull only what a program needs.
- **Users add their OWN restrictively-licensed ontologies** (SNOMED/MedDRA/LOINC…) into
  the user catalog dir already built; we never ship those.
- **License NOTIFICATION, not enforcement.** Surface a license note per aligned
  term/ontology and warn when a term comes from a restrictively-licensed source,
  especially if the action would store/redistribute it. Remote reference = OK
  (informational only). OLS4 does not reliably expose per-ontology license text
  (verified: `ncit` license empty), so notification uses a curated known-restrictive
  prefix list (SNOMED, MedDRA, LOINC, ICD, GMDN…) plus whatever the source provides.

Implication: **P1 (public OLS4 API) is the primary, fully license-clean path** (remote
reference, wide net, domain-filterable, nothing hosted/committed). Self-hosted OLS4
narrows to (a) air-gapped/regulated and (b) the user's own licensed ontologies — both
loaded on demand from sources at build time. Every aligned term persists source prefix +
IRI + versionInfo + license flag.

## Verified API facts (2026-07-05)

- `GET /api/search?q=Search&rows=3` → `numFound=1551`; docs carry `iri, ontology_name,
  ontology_prefix, short_form, description, label, obo_id, type, exact_synonyms`. Top hit
  `NCIT:C54117` (class).
- `GET /api/ontologies/ncit/terms?iri=<enc>` → `annotation.Semantic_Type="Activity"` for
  NCIT Search — the signal the Capability-slot validation reads.
- OLS4 `ncit` metadata did not populate a license string → notification uses a curated
  prefix list, not OLS4 metadata.
