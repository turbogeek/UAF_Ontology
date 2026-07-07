# Compound concepts — design (2026-07-07)

**Owner need:** an element can currently take only a FLAT set of ontology concept IRIs
(`rdf:type A, rdf:type B, …`). When no single concept captures the element's meaning, the modeler
needs to **say what it is by combining concepts**, and have it **read correctly in SBVR**.

## The model (genus + differentia)

A compound concept = a **genus** ("is a kind of", the pinned base concept) + zero or more
**differentia**, each a *(relation phrase, qualifier concept)* pair.

> genus `Drone` + `[(suppresses, Mosquito), (uses, Chemical Sprayer)]`
> → **"A Mosquito Suppression Drone is a Drone that suppresses a Mosquito and uses a Chemical Sprayer."**

## Storage — no profile change (DONE)

It rides on the existing multi-valued `mappedConceptURI` tag (`CompoundConcept`, committed):
- index 0 = the genus (a bare IRI — the existing pinned base);
- each later value is a bare IRI (an extra type) **or** `"<relation phrase> | <IRI>"` (a differentia).

Bare IRIs stay backward-compatible with today's flat alignments. `CompoundConcept.parse/​toStoredList`
round-trip it; `SBVREngine.generateCompoundSBVR` renders the sentence. **Unit-tested.**

## Remaining wiring (follows)

1. **Sidebar UI** — the big piece. When 2+ concepts are chosen, let the modeler mark one as the
   genus and give each other a **relation phrase**, then "Apply as compound concept". Show the
   live SBVR as they build it. Options for picking the relation → see the open question below.
2. **Apply/store** — `applyMappingsFromUI` encodes the clauses via `CompoundConcept.encode` into
   `mappedConceptURI`. `getMappedConcepts`, the audit, and the exporter must treat each value as
   `decode(value).conceptIri()` for type assertions (tolerate the `rel | IRI` form).
3. **RDF export** — emit the element's compound as a **defined concept** under the model root IRI:
   `<root>#<Name> rdfs:subClassOf <genus>` + for each differentia a restriction/annotation
   (`rdfs:subClassOf [ owl:onProperty <rel> ; owl:someValuesFrom <qualifier> ]` when the relation
   maps to a property, else an annotation) + `skos:definition "<SBVR sentence>"`. This is the
   UC-2.7 "local overlay concept" that never mutates the upstream ontologies.
4. **SBVR display** — the sidebar/selection SBVR shows the compound sentence when clauses exist.

## Open questions (owner)

- **Relation vocabulary**: a curated dropdown (e.g. *has function · targets · is part of · performs ·
  measured by · made of · located in · caused by*) — pick per differentia — vs. free-text vs. drawing
  relations from the loaded ontologies' object properties (BFO/CCO relations).
- **UI shape**: an inline relation dropdown per selected suggestion row (lightweight), or a small
  "Compose concept" dialog (genus + rows of relation→concept + live SBVR preview).
