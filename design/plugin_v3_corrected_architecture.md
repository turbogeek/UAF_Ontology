# v3 Architecture Correction — automatic UUID-based alignment (2026-07-05)

Owner feedback made clear I was looking in the wrong place. Two corrections and a
reframed alignment model.

## What was wrong

1. **Profile-module rabbit hole.** I spent cycles trying to build/mount a *new*
   "Semantic Alignment Profile" module to hold a `SemanticAlignment` stereotype. But
   UAF elements **already carry UAF profile stereotypes with stable IDs (UUIDs)**. I do
   not need to create or mount a profile to *identify* an element — I read its existing
   stereotype and match by ID. (Storage of the user's refinement is a separate, smaller
   concern.)
2. **Not using the shipped sample models.** Real UAF 1.3 samples ship at
   `E:\Magic SW\CMSoS26xR1pr\samples\UAF v1.3\` (UAF SAR Sample, UAF BoK Sample, Home
   Appliances Enterprise (DoDAF), Mission Engineering SAR, ...). These are the models to
   load, instrument, and save under a new `SampleUAF1_3\` directory in the repo.
3. **Foundational linking too coarse.** `uaf_ontology.ttl` maps essentially everything
   to `gufo:Object` — so "ActualOrganization" doesn't yet carry a meaningful BFO/
   foundational equivalent that a user can narrow from.

## Corrected alignment model

**Automatic (no clicks):** element → its applied UAF stereotype (stable ID) →
UAF ontology concept → foundational/BFO equivalent. All by lookup.

**User narrows (few clicks):** from the auto-known concept, the user refines to a more
specific/domain concept:
- ActualOrganization → (auto) org/BFO organization → narrow to *Services Organization*
- ActualPost → (auto) UAF Post + BFO/role equivalent → narrow to *Manager* / *Engineer*
- ResourceArtifact "Battery" → (auto) resource/artifact → narrow to a specific battery
  type (domain: EMMO/BattINFO) or, as an ActualResource instance, a battery of that type
  with a **charge-level property that itself carries semantic meaning**.

**Battery test matrix (owner):** Battery appears across viewpoints and levels —
Operational (context), Resources (specific *type* of battery), and an *actual instance*
of a battery of a specific type with a specified charge level. Type-level and
instance-level semantics both required; property values (charge level) are semantic.

## Stable UAF stereotype IDs (seed for the auto-match table)

Captured live from the UAF SAR Sample (`ProbeUafStereotypes`). These IDs are stable
across projects because they belong to the shared UAF profile.

| Stereotype | ID | UAF ontology IRI (target) |
|---|---|---|
| ActualOrganization | _15_1_f00036a_1212649837750_530959_148054 | uaf:ActualOrganization |
| ActualPost | _15_1_f00036a_1212649837750_161657_148053 | uaf:ActualPost |
| ActualPerson | _16_5beta2_8f40297_1237891237085_534216_4100 | uaf:ActualPerson |
| ActualResource | _18_1_90d02a1_1420635446081_698654_4841 | uaf:ActualResource |
| Organization | _15_1_f00036a_1212649837750_29451_148049 | uaf:Organization |
| Post | _15_1_f00036a_1212649837750_176846_148050 | uaf:Post |
| Person | _16_5beta2_8f40297_1237891492835_992188_4134 | uaf:Person |
| OperationalPerformer | _15_1_f00036a_1212649835688_312195_49696 | uaf:OperationalPerformer |
| OperationalActivity | _15_1_f00036a_1212649835672_434798_49688 | uaf:OperationalActivity |
| Capability | _15_1_f00036a_1212649835563_629884_49577 | uaf:Capability |
| ResourceArtifact | _15_1_f00036a_1212649835594_786592_49618 | uaf:ResourceArtifact |
| ResourceArchitecture | _15_1_f00036a_1212649835610_295695_49625 | uaf:ResourceArchitecture |
| CapabilityConfiguration | _16_9_8f40297_1283512444898_303891_15796 | uaf:CapabilityConfiguration |

(Full set: 522 distinct stereotypes in the sample; the table above is the alignment-
relevant core. The mapping ships as a resource so it is data, not code.)

## Work implied (for confirmation before building)

1. **Auto-match resource**: `uaf-stereotype-map.properties` (stereotype ID → uaf: IRI),
   loaded at init. On selection, read applied stereotypes, map by ID, show the concept
   automatically (0 clicks). Suggestion engine's role becomes *narrowing* (subclasses +
   domain/foundational refinements), not first-hit search.
2. **Foundational linking in uaf_ontology.ttl**: replace the blanket `gufo:Object` with
   meaningful equivalences/subclass links to gUFO (and BFO where appropriate) so the
   auto-known UAF concept yields a real foundational equivalent to narrow from.
3. **Sample instrumentation**: load a UAF sample, auto-align + let user narrow, save to
   `SampleUAF1_3\` as regression fixtures.
4. **Battery scenario tests**: type-level (ResourceArtifact battery type → EMMO/BattINFO)
   and instance-level (ActualResource battery instance with semantic charge-level).
5. **Profile/storage**: the shipped-profile-module mounting is portability-blocked; the
   in-project fallback keeps mapping working. Revisit storage once the UUID-auto-match
   lands (storing only the user's *refinement*, since identification no longer needs it).

## Note on the profile-module blocker

`exportModule`-built `.mdzip` fails `useModule`/`importModule` with "Module not found":
the exported module references its origin project's UML Standard Profile, which does not
resolve in a different project. Proper portable-module authoring (referencing the shared
standard profile) is non-trivial; the CAMEO-MCP-Bridge repo (166 tools) may offer a
cleaner path. Parked pending owner direction, with a working in-project fallback.
