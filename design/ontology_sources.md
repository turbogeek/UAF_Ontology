# Ontology Sources — Federation Survey & Roadmap (2026-07-06)

Owner: "we need more ontologies; OLS is very limited." OLS4 (EBI) indexes ~280 mostly
life-science ontologies — thin for systems engineering / UAF and the owner's priority
domains (medical devices, drug manufacture, governance/certification/approval,
manufacturing). This doc surveys the landscape (incl. openCAESAR) and lays out a
federation plan for the plugin's `TermSource` interface + on-demand catalog. Companion:
[ols4_integration_recommendation.md](ols4_integration_recommendation.md), [use_cases.md](use_cases.md) §3.1.

## The key architectural insight: 3 generic adapters unlock ~everything

Rather than one class per source, three parameterized adapters cover the whole landscape:

1. **`OlsTermSource(baseUrl)`** — the EBI OLS4 client generalized. The **TIB Terminology
   Service** (`https://api.terminology.tib.eu/api`) uses the *identical* OLS response shape
   (`response.docs[]` with iri/label/ontology_name/…), so a second instance adds 100+
   **engineering / physics / chemistry / materials** ontologies with **zero parser code**.
   Keyless, remote-reference (license-clean). **← DONE (see below).**
2. **`OntoPortalTermSource(baseUrl, apiKey)`** — every OntoPortal deployment exposes the
   same `GET {base}/search?q=&apikey=` JSON-LD surface (`@id`→IRI, `prefLabel`, `synonym[]`,
   `links.ontology`→source). One class, N config rows, covers **IndustryPortal**
   (manufacturing/Industry-4.0/SE + hosts CCO/IOF/BFO/MASON), **BioPortal** (~1000+, incl.
   medical-device vocabularies), **MatPortal** (materials), and AgroPortal/EcoPortal for free.
   Needs a free API key per portal. The adapter reads `links.ontology` to **flag/skip
   restricted** ontologies (SNOMED/MedDRA/UMLS) per the notify-on-restricted policy.
3. **`GenericSparqlTermSource(endpoint, labelTemplate)`** — one `rdfs:label`/`skos:prefLabel`
   regex query maps `?iri/?label/?source` for endpoint-only sources: **QUDT** live, **Wikidata**
   (`query.wikidata.org`), **ISO-15926 PCA RDL**, DBpedia, Ontobee.

Plus the **on-demand catalog** (existing Jena importer) for downloadable permissive OWL/TTL:
CCO, BFO-2020, IOF, QUDT TTL, schema.org, gUFO, W3C PROV-O/ODRL/DPV, and the openCAESAR OML
vocabularies (via `oml2owl`). Import shared uppers FIRST (BFO→CCO→IOF) so IRIs cross-reference;
pick ONE foundational family (BFO/CCO/IOF **or** UFO/gUFO), don't mix.

## openCAESAR / OML — yes, consumable as OWL

openCAESAR (JPL/Caltech, Apache-2.0) publishes **OML** (Ontological Modeling Language), a
DSL "inspired by OWL2+SWRL" that maps every construct to OWL2-DL (spec §8). The
[owl-adapter](https://github.com/opencaesar/owl-adapter) `oml2owl` Gradle/CLI task emits
OWL2-DL from any OML vocabulary. Run it once at build time, then index the OWL like any
other file. The valuable vocabularies:
- **[core-vocabularies](https://github.com/opencaesar/core-vocabularies)** — reusable upper
  primitives (import first).
- **[imce-vocabularies](https://github.com/opencaesar/imce-vocabularies)** — `base`,
  `mission`, `analysis`, `project` foundation (systems/mission engineering) — aligns tightly
  with UAF elements; the maintained successor to the now-archived
  [JPL-IMCE ontologies](https://github.com/JPL-IMCE) (read-only since 2026-01-19).
- **metrology-vocabularies** (units/quantities, complements QUDT), **provenance-vocabularies**
  (governance lineage, complements PROV-O), and a **SysML v2 ontology** (metamodel-level).

## Ranked additions (most value / least effort first)

| # | Source | Domains | Access | License | How | Effort |
|---|---|---|---|---|---|---|
| **1 ✅** | **TIB Terminology Service** | engineering, physics, chem, materials | REST (OLS API), keyless | Apache-2.0 sw / open content | 2nd `OlsTermSource` instance | **done** |
| 2 | **OntoPortal adapter** → IndustryPortal + BioPortal + MatPortal | manufacturing/SE, medical, materials | REST JSON-LD, free key/portal | mixed (flag restricted) | one adapter, N configs | low |
| 3 | **CCO** (Common Core Ontologies) | UAF Resources/Orgs/Actuals backbone (BFO-based, DoD-aligned) | download TTL | BSD-3 | catalog import (+ BFO-2020) | low |
| 4 | **QUDT** | units/quantities → MOE/TPM | download TTL or SPARQL | CC-BY-4.0 | catalog import (or SPARQL) | low |
| 5 | **openCAESAR imce+core** | systems/mission engineering uppers | OML→OWL (`oml2owl`) | Apache-2.0 | build step + catalog import | medium |
| 6 | **openFDA** | device classifications/510k/PMA/UDI, drug NDC/labels | REST, keyless (key raises limits) | public domain | `TermSource`, synthetic IRIs | low |
| 7 | **RxNorm/RxNav** | drug names (fuzzy `/approximateTerm`) | REST, keyless | public domain | `TermSource` | very low |
| 8 | **IOF** (Industrial Ontologies Foundry) | manufacturing, biopharma, quality | download OWL | MIT (per-module) | catalog import (reuses CCO/BFO) | medium |
| 9 | **W3C PROV-O + ODRL + DPV** | governance/certification/approval, audit, policy, privacy | download TTL | W3C open | catalog import (governance bundle) | low |
| 10 | **LOV** (Linked Open Vocabularies) | cross-domain web vocabularies (ORG, DCAT, PROV) | REST, keyless | CC-BY-4.0 | `TermSource` | low |
| 11 | **Wikidata** | broad fallback: orgs, standards, certifications | REST `wbsearchentities` + SPARQL | CC0 | `TermSource` / SPARQL adapter | low |
| 12 | **ISO-15926 PCA RDL** | plant/asset/industrial lifecycle | public SPARQL | RDL queryable; standard text ISO-© | SPARQL adapter, cache | medium |

**Restricted — notify only, never bundle** (owner policy): GMDN, SNOMED/UMLS/MedDRA,
DrugBank (full DB). Free substitutes: GMDN→openFDA/AccessGUDID UDI; DrugBank→DrugBank Open
Data (CC0); SNOMED→only via BioPortal's license-acceptance flow or user-supplied UTS creds.
**BARTOC** (2740+ KOS registry) is a *discovery* tool to find more sources, not a live source.

## Quick wins (recommended order)

1. **TIB via the OLS adapter — DONE.** Federated EBI OLS4 + TIB; verified live ("gearbox" →
   7 results from 2 sources incl. Open Energy Ontology). Closes the SE breadth gap, no new
   parser, keyless. Configurable: `-Dsemantic.plugin.ols.endpoints=<base1>,<base2>,…`.
2. **OntoPortal adapter** (IndustryPortal + BioPortal + MatPortal) — one class, needs free
   API keys per portal (owner action). Biggest single leverage: 4 priority domains at once.
3. **Governance + backbone catalog bundle** — CCO + BFO-2020 + QUDT + PROV-O/ODRL/DPV
   (all permissive, offline). Seeds the UAF Resources/Orgs/Measures backbone *and* the
   governance/certification domain with no runtime API dependency.
   Optional 4th: **RxNorm** `/approximateTerm` (keyless drug fuzzy search).

## License posture (owner: reference remotely OK; notify on restricted; never redistribute)
- **Remote-reference (clean to query live):** TIB, OntoPortal REST, openFDA, RxNorm,
  Wikidata, ISO-15926 SPARQL, NCIt EVS.
- **Catalog-import safe (permissive):** CCO (BSD-3), IOF (MIT), BFO/QUDT/PROV-O/ODRL/DPV/gUFO
  (CC-BY / W3C), openCAESAR (Apache-2.0). Watch share-alike: schema.org (CC-BY-SA), DBpedia
  (CC-BY-SA) — prefer Wikidata (CC0).
- **Never bundle:** GMDN, SNOMED/UMLS/MedDRA, DrugBank full DB.

## Status
- ✅ **DONE:** OLS-family federation (EBI OLS4 + TIB), verified live. `Ols4TermSource` is the
  parameterized adapter; `ONLINE_SOURCES` in `SemanticAlignmentPlugin` builds the list
  (override via `-Dsemantic.plugin.ols.endpoints`). Sidebar button relabeled "Search Online
  Ontologies"; results merged + de-duped by IRI across sources.
- **NEXT (owner to prioritize):** OntoPortal adapter (needs free keys) → governance+backbone
  catalog bundle → openCAESAR OML import → openFDA/RxNorm → SPARQL adapter (QUDT/Wikidata).
