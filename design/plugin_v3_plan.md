# Semantic Alignment Plugin v3 — Usability-First Plan

Date: 2026-07-05. Baseline: v2.2.0 (commit 4bc3658) — deployed, GUI-integration-tested
(IT0–IT5 green), pure-Swing sidebar, integrated REST harness on 8765, self-driving
test cycle. Purpose of v3: make "instrument any model with semantics of an appropriate
ontology" **usable** — fast alignment with narrowed choices, a visible resulting
ontology, queryability, and reasoning the user can see.

Grounded in: `cameo_plugin_mockup.html` (the intended interface), `design/plugin_design.md`,
`tutorial/FOUNDATIONAL_ONTOLOGIES.md` + `HOW_TO_USE_ONTOLOGY.md` (methodology),
`UAF to OWL Goals/ontologies/` (catalog), `graphify-out/` (model knowledge graph:
1542 nodes / 1636 edges / 14 semantic communities).

---

## What the assets tell us

**The mockup's interface contract** (what v2's sidebar must become):
five cards — element metadata → color-coded SBVR → **search & align with autocomplete
narrowing (single click applies the mapping)** → governance dashboard (status badge +
terminal-style audit trace with reasoner timing) → contextual help. Mapping stores
`mappedConceptURI` + `ontologySource` + `mappingConfidence`.

**The ontology catalog is autocomplete-ready**: near-100% `rdfs:label` coverage —
uaf_ontology 582 labels, uafsml_ontology 522, ORG 189 (plus comments, multilingual),
gUFO 97, BMM 37. Total ≈ 1,400 concepts — trivially an in-memory index. gUFO carries
22 `owl:disjointWith` axioms (real reasoning fuel); ORG/BMM carry domain/range axioms.

**Critical catalog gap**: uaf_ontology.ttl and uafsml_ontology.ttl have **no
disjointness axioms** — a UAF-typed model can never be found inconsistent today.
**Decision (owner, 2026-07-05): integrate disjointness directly into the ontologies we
control** (uaf_ontology, uafsml_ontology, and likewise kerml/sysml2/UML as applicable),
NOT as a separate overlay file. Flagship error class to catch: **actual elements placed
in logical views** — e.g., designers putting real organizations (`ActualOrganization`)
into the operational view, which is meant to be logical (`OperationalPerformer`/
`OperationalAgent`); logical-vs-actual disjointness makes that a provable inconsistency.
General principle: we own these ontologies, so axiom improvements (disjointness,
domain/range) are welcome wherever they let the reasoner find real model errors.

**Graphify** captures the whole corpus as a knowledge graph with community clustering —
usable as (a) a co-occurrence prior for suggestion ranking ("elements in this cluster
usually map into ORG"), (b) the basis for the graph visualization tab, (c) scenario seeds.

**Reasoner architecture** (investigated against the install): Concept Modeler ships
OWLAPI 5.1.19 + HermiT + Jena 5.6.0 — but depending on it via `required-plugin` would
collide with our Jena 4.6.1 and lock us to its versions. Verdict: stay isolated
(`ownClassloader=true`), bundle **OWLAPI 5.5.1 + ELK 0.6.0** in P3 (the proven pattern
from OntologyForMuggles' build), keep Jena's rule reasoner for interactive inference,
and pin `jena-arq:4.6.1` for SPARQL (already transitively present; needs explicit
`ARQ.init()` at plugin start to fire its ServiceLoader deterministically).

---

## 1. Alignment UX — kill the CURIE field

Flow: select element → sidebar instantly shows **top-5 ranked concept suggestions with
zero keystrokes** → one click applies the mapping (session transaction, undoable) →
SBVR card re-renders as the confirmation trace. If no suggestion fits: type 2–4 chars
in the search field → debounced popup narrows → Enter/click applies.

**Click budget (asserted by tests, not aspirational): 2 clicks + 0 keystrokes best
case; 2 clicks + ≤4 keystrokes with search.** v2 baseline was 1 click + ~50 keystrokes.

Ranking signals (package `...semantic.align`):
- `ConceptIndex` — label/altLabel/comment/token inverted index over the catalog,
  built by `CatalogLoader` at init on a worker thread; **fails loudly if empty**
  (cache-validation rule); stats journaled.
- `StereotypeRouter` — editable `routes.json`: applied stereotype → boosted namespaces
  (ActualOrganization/ActualPost→org:, Capability/Goal→bmm:+uaf:, blocks/resources→
  sumo:+domain, Requirement→uafsml:).
- `SuggestionRanker` — exact label 1.0 > altLabel 0.9 > prefix 0.7 > substring 0.5 >
  fuzzy token 0.35, +0.25 stereotype-routed namespace, +0.2 name-token overlap,
  (+0.15 graphify co-occurrence prior in P3). Top-8 returned.
- UI: `SuggestionListPanel` (always-visible ranked list), `ConceptSearchField` (150 ms
  debounce), `SuggestionPopup` (keyboard-navigable; ontology badge color per prefix;
  tooltip = rdfs:comment). All components named `semantic.*` for GUI tests.

**Bulk alignment**: multi-select or right-click package → "Align children…" →
`BulkAlignmentPanel` JTable: Element | Metaclass | Top suggestion (combo of that
element's ranked list) | Confidence | Status; "Apply all ≥ 0.80" commits one session
(one undo step). Budget: **10 elements in ≤5 clicks**.

## 2. Ontology view (new browser window, per-project like the sidebar)

**Audience split (owner requirement): ~90% of users are Muggles** — non-ontologists.
The default view is plain English; expert views exist but never lead.

- **SBVR tab (P1, the DEFAULT tab)**: the resulting ontology rendered as SBVR
  Structured English via the existing SBVREngine (one sentence per fact:
  instantiation, containment, associations, generalization), grouped by element,
  color-coded per the mockup. Precedent: `msar_instances-sbvr.html`. This is the
  Muggle window into the ontology.
- **Turtle tab (P1, expert)**: read-only serialized project graph, "include inferred"
  checkbox, Save .ttl.
- **Tree tab (P2)**: class hierarchy of namespaces actually used → instances beneath;
  **inferred types/nodes in italic with distinct icon** (InfModel − base model);
  double-click instance → selects the element in the containment tree (exporter's
  IRI↔elementID map).
- **Graph tab (P3)**: self-contained HTML via the graphify viewer, community-colored,
  opened with Desktop.browse().

**Display scale/cost is dynamic (owner requirement)**: no view materializes the whole
graph eagerly. SBVR and Turtle render the first N facts (default 500) with a shown/total
count and "Load more" / "Save full file…" actions; the tree lazy-loads children on
expand; renders happen off the EDT with the result swapped in. Budgets asserted by
tests: first paint of any tab ≤ 500 ms at tutorial scale, and opening a view must never
freeze Cameo regardless of model size.

**Muggle guidance (P2)**: a "Guide" panel — stereotype-routed explanations and worked
examples per design spec 12.3 (Capability → strategic taxonomy guide, ActualOrganization
→ org guide, battery → BattINFO guide), each showing the SBVR translation pattern and an
example alignment. Backlog (post-P3): LLM assistant surface on top of the same guide
content and /sparql endpoint.

## 3. Query — SPARQL panel + REST

`SparqlQueryPanel`: canned-query combo → editable text → Run (background) → results
table with "go to element" for model IRIs → CSV export. Dataset = catalog TBox +
project ABox, "with inference" toggle. Plus REST **`/sparql`** and **`/metrics`** on the
integrated harness so scenarios assert results headlessly.

Canned queries (each demonstrates value on a tutorial scenario):
1. **Alignment coverage / semantic gaps** — unmapped elements + coverage %.
2. **Capability without performer** (Tutorial C / Hoth) — the capability-gap detector.
3. **Org rollup** (Tutorial A) — post→unit→org transitive chains; only complete with
   inference ON (visible reasoner value).
4. **Battery taxonomy** (Tutorial B) — custom `ev:LithiumSulfurCell` appears under
   `battinfo:BatteryCell` queries only with inference ON.
5. **UAF 1.3 ↔ UAFSML 2.0 equivalence** via uaf_bridge — dual typing report.

## 4. Reasoner — two tiers, both visible at the element

- **Tier 1 (interactive, P2)**: Jena rule reasoner (already bundled) feeds the ontology
  tree, SPARQL inference toggle, and a new "Semantics" section on the metadata card:
  asserted types, *inferred types*, red violation rows (SHACL message; for disjointness
  the two conflicting assertions = a minimal explanation).
- **Tier 2 (audit, P3)**: separate `reasoner` Gradle module bundling OWLAPI 5.5.1 + ELK
  0.6.0; classification + consistency with justifications; optionally emit findings into
  Cameo's standard validation results window.
- **Catalog work (P1, prerequisite — moved up per owner)**: integrate disjointness
  axioms directly into uaf_ontology.ttl and uafsml_ontology.ttl (and kerml/sysml2 where
  meaningful), including logical-vs-actual disjointness (OperationalPerformer et al.
  disjoint from ActualOrganization/ActualPerson/ActualResource) so misplacing actual
  elements in logical views becomes a detectable inconsistency. Each axiom set gets a
  regression test proving the reasoner catches the target error.

## 5. Scenario automation — reduce clicks, prove it

- `UxMetrics`: AWT event counter scoped to plugin components; journaled +
  `/metrics` + `/metrics/reset` REST. Every scenario asserts its click budget.
- New harness tests: IT6 suggest-and-align (top-1 = org:Organization for an
  ActualOrganization; ≤2 clicks), IT7 bulk-align (10 elements, 1 undo step, ≤5 clicks),
  IT8 query panel (canned query bindings via /sparql; italic inferred node in tree),
  IT10 reasoner value (query-4 row-count delta with inference on/off).
- **Scenario suites SA/SB/SC = design Tutorials A/B/C** end-to-end: fixture → scripted
  GUI alignments → audit → queries → metrics → cleanup → shutdown. Wired into
  run-integration-tests.ps1; all evidence in readable logs.

Budgets asserted: single align ≤2 clicks/≤5 keys; bulk-10 ≤5 clicks; audit-to-first-
violation ≤2 clicks; query answer ≤3 clicks; suggestion latency ≤200 ms; Tier-1
inference ≤1 s; audit ≤2 s (mockup promised 0.84–1.12 s).

## 6. Phasing (each phase ends: unit + integration suites green, committed)

- **P1 "Align fast" (~3–5 days)**: ConceptIndex + ranking + suggestion UI, CURIE field
  removed, SPARQL panel + 5 queries + /sparql + /metrics + UxMetrics, ontology view
  with SBVR (default) + Turtle tabs (dynamic/lazy rendering), disjointness integrated
  into the UAF ontologies + reasoner regression tests, jena-arq pinned.
  Tests IT6 + IT8-lite.
- **P2 "See and trust" (~1–2 weeks)**: ontology tree (asserted vs inferred), bulk table,
  element-level semantics + violations, uaf_disjointness.ttl, suites SA/SB, IT7/IT8.
- **P3 "Deep reasoning + context" (~2–3 weeks)**: ELK/OWLAPI module + justifications,
  Cameo validation window integration, graphify graph tab + co-occurrence ranking,
  extension wizard (custom subclass with pre-persist consistency check), suite SC,
  optional BioPortal.

## Decisions (resolved with owner, 2026-07-05; revised same day)

1. **UAF 1.3 first** (`uaf_ontology`): polish the 1.3 experience end to end — routing
   defaults, pins, SBVR vocabulary, canned queries. KerML/SysMLv2/UAFSML follow after
   1.3 is polished. Disjointness improvements still land in every ontology we control.
2. **Reasoner: non-denominational** (per VOM / OntologyForMuggles experience) — we do
   not yet know which engine suits this workload. Reasoning goes behind a
   `ReasonerAdapter` abstraction (selected via `-Dsemantic.plugin.reasoner`, default
   `jena-rules`); ELK/HermiT/Openllet become pluggable candidates to be *benchmarked*,
   not a committed choice.
3. **External ontologies: on-demand import is the requirement** (BioPortal is just one
   transport). Driving scenario: **medical devices** (primary) and **drug manufacture**
   (secondary), where the critical ontologies concern **governance, certification, and
   approval of devices** (regulatory: FDA/MDR/UDI/ISO-13485 territory). Mechanism: a
   user catalog directory that survives redeploys + runtime re-index (no restart), so
   any acquired ontology drops in and is immediately alignable. Backlog (LLM API
   effort): **building ontologies from documents** — extract candidate
   classes/relations from regulatory documents into draft TTL for curation.
4. **Disjointness lives IN the ontologies we control**, not overlay files (see above).
5. **Ontology view leads with SBVR English** (Muggle-first); Turtle/Tree/Graph are
   expert tabs; all views render lazily with dynamic scale limits.
6. **Code coverage is part of the definition of tested**: JaCoCo wired into the Gradle
   build; coverage reviewed per change so tests are demonstrably exercising the logic
   they claim to.
