# Semantic Alignment — Use Cases & Intent (2026-07-06)

Authoritative, owner-confirmed statement of *what* the Semantic Alignment tool is for,
expressed as concrete use cases. Companion docs: [profile_storage_mechanism.md](profile_storage_mechanism.md)
(Feature 1 — how alignments are stored), [ols4_integration_recommendation.md](ols4_integration_recommendation.md)
(Feature 2 — external term sources). This file is the "why"; those two are the "how".

---

## 0. The vision in one paragraph

Instrument any UML/SysML/UAF model with the **semantics of appropriate ontologies** so
the model's meaning is captured as correct, rich, machine-usable data — not just human
prose. Once the meaning is captured, four very different consumers can use it:

1. **Ontologists** — validate that the model says what it means in a canonical,
   disjoint-where-it-should-be, foundationally-grounded vocabulary.
2. **Muggles** (reviewers, managers, customers, domain experts who are *not* ontologists)
   — read the same meaning back as plain controlled English (SBVR) and agree or object.
3. **A reasoner** — validate consistency, classify, and derive entailments (e.g. detect
   that an element aligned as a *Capability* has been mis-mapped to an *Activity*).
4. **An LLM** — consume the correctly-specified rich data to *review*, to *iterate on new
   detail*, and to *solve posed problems* — e.g. propose Measures of Effectiveness (MOE),
   Technical Performance Measures (TPM), and improvements to systems, subsystems, or parts.

The tool succeeds when the *same* captured meaning serves all four audiences without any
of them having to trust a hand-written comment.

---

## 1. Worked example — the pest-suppression drone

A mosquito- (or other pest-) hunting/killing drone. This example is deliberately chosen
because it must be expressed **from several UAF viewpoints** and **at differing levels of
detail**, and because its top-level requirement is only meaningful when grounded in the
capabilities of its parts.

### 1.1 The same subject across viewpoints (breadth) and detail (depth)

| UAF viewpoint | What appears | Example semantic alignment |
|---|---|---|
| **Strategic (St)** | Enterprise goal / capability | *Area Pest Suppression* capability; *Vector-borne-disease Reduction* goal |
| **Operational (Op)** | Operational performers & activities | *Detect target*, *Classify target*, *Neutralize target* activities; *Surveillance Node* performer |
| **Services (Sv)** | Services offered | *Aerial Suppression Service* |
| **Personnel (Pers)** | Posts / roles | *Operator* post; *Mission Planner* post |
| **Resources (Rs)** — type | Resource artifacts (the design) | *Suppression Drone* artifact and its parts: *optical sensor*, *targeting effector*, *battery* |
| **Resources (Rs)** — instance | Actual resources (a specific unit) | *Drone S/N 0007* with a **charge level** (a semantic quantity, not a label) |

The **breadth** (multiple viewpoints) and **depth** (strategy → operational → resource
type → resource instance) both matter: the tool must let the *same* real-world concept —
e.g. "Battery" — be aligned appropriately wherever it appears, and let a reviewer/reasoner
see that these are views of one thing.

### 1.2 Derivable meaning vs. capability-quantified requirement

Two claims about the drone sit at very different semantic levels, and the tool must
support both:

- **"A bug-killing drone" is derivable from the assembly + context.** Given the parts
  (unmanned aerial platform + detection sensor + insect-neutralizing effector) and the
  operational context (activities *detect/classify/neutralize* against *insect* targets),
  a reasoner/LLM can *infer* "this assembly is a bug-killing drone" from the semantics
  alone — nobody has to assert it as a label. **This is entailment from structure.**

- **"A bug-killing drone that can kill up to 10,000 mosquitoes in a 2 km² area" is NOT
  derivable from structure alone** — it is a **quantified requirement grounded in the
  capabilities of the parts**: sensor detection range and accuracy, targeting accuracy,
  effector kill-rate, and endurance (battery). This is where **MOE/TPM** live: the number
  10,000-per-2 km² is a *Measure of Effectiveness* that must be *consistent with* the
  part-level Technical Performance Measures (battery Wh × kill-rate × coverage-rate ×
  accuracy). **This is a constraint over part semantics, checkable by a reasoner and
  proposable/critiqueable by an LLM.**

The design implication: element-level alignment (§2) captures *what each thing is*; the
derived ontology (§4) lets a reasoner/LLM compute *what the assembly therefore is* and
*whether the quantified claims follow*.

### 1.3 What the four consumers each do with this example

- **Ontologist:** confirms *Area Pest Suppression* is aligned to a Capability (realizable),
  not to the *Neutralize* Activity it realizes (see the capability/activity guard, §2 of
  the OLS4 doc); confirms *Battery* type vs. instance are aligned to type-level vs.
  individual concepts.
- **Muggle/reviewer:** reads "Each Suppression Drone has a battery that provides electrical
  energy storage; the drone realizes the Area Pest Suppression capability" in SBVR English
  and signs off — with no CURIEs on screen.
- **Reasoner:** flags an inconsistency if the 2 km² / 10,000-kill MOE contradicts the
  battery endurance TPM; classifies *Drone S/N 0007* as an *ActualResource* with a *charge
  level* individual.
- **LLM:** proposes additional MOEs (false-positive rate on non-target insects, coverage
  rate, sortie endurance) and TPMs (sensor angular resolution, effector dwell time,
  battery Wh); drafts new detail for an under-specified subsystem; answers "which part
  most limits the 2 km² claim?"

---

## 2. Feature 1 use cases — capturing & storing the meaning

Storage mechanism detail: [profile_storage_mechanism.md](profile_storage_mechanism.md).
Confirmed design decisions from the owner (2026-07-06):

- **UC-1.1 Instrument the model is an explicit user action.** A **Tools ▸ Semantic
  Alignment** submenu holds an **Instrument Model** command. Instrumenting *adds the
  Semantic Alignment profile to the model* deliberately (no silent mount-on-first-use).
  This is the moment the model opts in.
- **UC-1.2 A model-level stereotype maintains the root IRI + version.** Instrumenting
  applies a **model-level** stereotype (`SemanticModel`) to the model/package root,
  carrying the **ontology root IRI** (the namespace of this model's derived ontology) and
  its **version**, plus provenance (instrumented-by / -date). Every element-level
  alignment and the whole derived ontology hang off this root.
- **UC-1.3 Align an element (invisible, inspectable).** Selecting an element and choosing a
  concept stamps an *invisible* `SemanticAlignment` stereotype (tags: mappedConceptURI,
  ontologySource, mappingConfidence). It **never changes how the element renders** on any
  UAF/SysML diagram, but the tags are visible/editable in the element's **Specification**
  (via the Customization). Meaning travels *inside* the model.
- **UC-1.4 The meaning is portable & auditable.** Hand the `.mdzip` to a colleague, reopen
  it next year — the alignments are on the elements. An audit reads them all and rebuilds
  the ontology.
- **UC-1.5 Un-instrument the model (with warnings).** A **Remove Instrumentation** command
  removes all `SemanticAlignment` applications *and* the model-level `SemanticModel`
  stereotype, after an explicit warning (this deletes the captured semantics). The model
  returns to exactly its pre-instrumentation state.
- **UC-1.6 Generic, not UAF-specific.** The stereotypes extend the UML `Element` / `Package`
  metaclasses, so the same tool instruments plain UML and SysML 1.x models too.

---

## 3. Feature 2 use cases — reaching the right ontology (many sources)

Source/landscape detail: [ols4_integration_recommendation.md](ols4_integration_recommendation.md).
Confirmed direction from the owner (2026-07-06): **OLS4 is one of *many* sources.** The key
to initial utility is **breadth of domains** to map models against.

- **UC-2.1 One of many sources.** OLS4 (EBI, ~280 ontologies) is a `TermSource` behind an
  interface, beside the user's own imported ontologies and vendored local fragments. No
  single service has everything (OLS4 is life-science-centric; it lacks CCO and
  medical-device/regulatory vocabularies). The tool federates.
- **UC-2.2 Breadth-of-domains is the near-term goal.** Success = a modeler can find *a*
  reasonable concept for most elements across many domains. This drives a survey of the
  **landscape of ontology creators & hosts** (§3.1) so we know where to reach.
- **UC-2.3 Capability ≠ activity guard.** Searching "Search" for a UAF *Capability* returns
  NCIT:C54117 "Search", flagged by OLS4 as an *Activity*. The tool warns and routes it to
  the *realized-activity* slot, keeping a realizable concept in the capability slot.
- **UC-2.4 Domain filtering.** For a drug-manufacturing model, scope search to
  drug/chemistry ontologies (DRON, ChEBI); for the drone, to engineering/BFO/gUFO — so the
  wide net doesn't drown the modeler.
- **UC-2.5 Offline / regulated.** Point the same `TermSource` at a self-hosted OLS4 (config
  change only) loaded with just the licensed ontologies; restrictively-licensed terms
  (SNOMED/MedDRA/LOINC…) trigger a **notification** before storage.
- **UC-2.6 Provenance for audit.** Every externally-sourced alignment records IRI +
  ontology prefix + versionInfo + source base-URL.
- **UC-2.7 Extend or correct an existing ontology.** Aligning often reveals a **gap** (a
  concept the upstream ontology lacks, or a mis-specified/mis-labelled one). The tool lets
  the user propose an **extension or correction** — captured as a **local overlay
  ontology** (a subclass, an equivalence, a corrected label/definition) that **never
  mutates the upstream source**. The extension is **validated across several use-case
  models** (multiple models exercising the new concept) and by the reasoner before it is
  ever proposed upstream. This is how the tool grows the ontology landscape responsibly.
- **UC-2.8 Scope-aware context search.** The *same element name* must resolve differently
  depending on **where it sits in the model** — the wide net is useless if a part named
  `engine` returns a pump engine when it is bolted into an SUV. Three orthogonal signals
  (owner requirement, 2026-07-07), all computed in the service and verifiable off-line:
  1. **UAF layer -> abstraction.** The element's architecture layer shifts the search:
     an **Operational** element leans logical (`uaf` operational), a **Resource** element
     leans physical (`cco`/`sumo`/`qudt`), a **Strategic** element leans motivation (`bmm`).
     Configurable in `layers.properties` (overridable in the deployed plugin dir).
     *Verified:* `agent` as a **Resource** ranks `sumo:Agent`/`cco:Agent` above `prov-o:Agent`.
  2. **Construct kind -> BFO category.** The element's metaclass category biases toward the
     matching **BFO** upper category: a SysML **Activity/Action** (BEHAVIOR) prefers an
     *occurrent* (a process / "Act of ..."); a **Block/Part** (STRUCTURE) prefers a
     *continuant* (an object); a **ValueType** (VALUE) prefers a *quality*. The classifier
     is the loaded ontology's own `rdfs:subClassOf*` grounding, not a keyword list. Crucially,
     a construct-kind conflict **revokes the exact-name-match privilege**, so an *Activity*
     named like an object is not pinned to the object. *Verified:* `government` as a BEHAVIOR
     ranks "Act of Government" above the exact object "Government"; as a STRUCTURE, the reverse.
  3. **Structural context -> disambiguation.** The surrounding structure (owner name/type, the
     element's own type, sibling parts) becomes weighted context terms that boost concepts
     whose label/alt-labels/comment overlap them. *Verified:* `engine` with owner `aircraft`
     + type `jet` ranks **Jet Engine** (and the turbofan/turbojet subtree) above Steam Engine;
     swap to `locomotive`/`steam` and **Steam Engine** leads. This is the V8-in-an-SUV case.

  The plugin derives layer + construct kind from the selected element (pure
  `ScopeContext.deriveConstructKind(metaclass)`, unit-testable without Cameo) and sends them,
  with the context terms, to the service's `/suggest`. Empty context reproduces the prior
  ranking exactly, so the feature is strictly additive.

### 3.1 Ontology landscape — creators & hosts to survey (breadth backlog)

To attain breadth, know where ontologies live. Survey backlog (reference, don't host —
see the loading/licensing policy in the OLS4 doc):

- **Life science / bio:** EBI **OLS4**, **BioPortal** (NCBO), **OBO Foundry** (BFO, OBI,
  OGMS, ChEBI, DRON, NCIT slice).
- **Foundational / upper:** **BFO**, **gUFO/UFO**, **DOLCE**, **SUMO**, **Common Core
  Ontologies (CCO)** (BFO-based, DoD/UAF-aligned — *not* in OLS4; vendor from GitHub).
- **Industrial / engineering:** **Industrial Ontologies Foundry (IOF)**, **OntoCommons**,
  **NIST** (e.g. manufacturing), **EMMO/BattINFO** (materials & batteries — relevant to the
  drone's battery).
- **Standards bodies / web:** **W3C** (ORG, SKOS, PROV, QUDT for quantities/units),
  **OMG** (SysML/UAF/KerML metamodels themselves), **schema.org**.
- **Regulatory / device (owner-imported, on demand):** IMDRF, GMDN, IEC 62304, ISO 14971 —
  not in OLS4; imported into the user catalog when a program needs them.

---

## 4. Cross-cutting — the ontology is *maintained from the annotations*

Owner requirement (2026-07-06): the derived ontology must be **kept in sync with the model's
annotations** so all downstream tooling reads correct, current data.

- The **derived ontology is a projection** of the model's alignments, anchored at the
  model-level root IRI + version (UC-1.2) and rebuilt from the element-level alignments
  (UC-1.3) by the RDF exporter.
- Everything downstream — **SBVR** English, **SPARQL** queries, the **reasoner**, and any
  **LLM** task — reads that projection, never the raw model. So the invariant is: *change
  an annotation → the projection reflects it → all four consumers see the change.*
- This is why the root IRI/version lives on a model-level stereotype: it gives the
  projection a stable identity and lets audits compare versions over time.

---

## 5. Traceability of these use cases to build work

| Use case | Where it is built / to be built |
|---|---|
| UC-1.1 explicit Instrument action | `SemanticMenuConfigurator` + Tools submenu (in progress) |
| UC-1.2 model-level root IRI + version | `SemanticModel` stereotype in the shipped profile (in progress) |
| UC-1.3 invisible element alignment | `StereotypeManager` + Customization (done) |
| UC-1.4 portable/auditable | RDF exporter + SHACL/reasoner (done) |
| UC-1.5 un-instrument | `SemanticMenuConfigurator` Remove Instrumentation (in progress) |
| UC-1.6 generic profile | profile extends UML Element/Package (done) |
| UC-2.1 many sources | `TermSource` seam, `Ols4TermSource` (done); catalog federation (done) |
| UC-2.3 capability/activity guard | `CapabilityGuard` (done) |
| UC-2.5 offline/regulated + license notify | base-URL config + `OntologyLicenses` (done) |
| UC-2.7 extend/correct ontology | overlay-ontology authoring + multi-model validation (backlog) |
| UC-2.8 scope-aware context search | `ScopeContext` + `ConceptCategoryIndex` (BFO) + `LayerRouter` + scoped `SuggestionRanker`; service `/suggest` `context`; `CatalogServiceClient` (done, service-side; plugin element→context derivation = in progress) |
| §3.1 landscape survey | breadth backlog (research) |
| §4 ontology maintained from annotations | exporter projection anchored at root IRI/version (partly done; root IRI = in progress) |
