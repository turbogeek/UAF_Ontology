# Profile Storage & Asset Loading — mechanism established, authoring blocked (2026-07-05)

> **Use cases:** see [use_cases.md](use_cases.md) §2 for what this storage feature is *for*.
>
> **Owner refinements (2026-07-06):**
> - **Instrument is an explicit user action**, not silent mount-on-first-use — a
>   **Tools ▸ Semantic Alignment ▸ Instrument Model** command adds the profile to the model.
> - **Add a model-level stereotype** (`SemanticModel`, extends `Package`) that maintains the
>   **ontology root IRI + version** (+ instrumented-by/-date provenance). It anchors the
>   derived ontology and lets audits compare versions.
> - **Un-instrument** (Remove Instrumentation) removes all `SemanticAlignment` applications
>   *and* the model-level stereotype, after a warning — returns the model to its clean state.
> - The derived ontology is **maintained from the annotations** (a projection rebuilt by the
>   exporter, anchored at the root IRI/version) so SBVR/SPARQL/reasoner/LLM read current data.

Owner requirement: ship the SemanticAlignment stereotype as a REAL profile via the
plugin's proper asset mechanism (not runtime `useModule` of a scratch module); make it
INVISIBLE (never changes how a UAF/UML element renders) with a Customization so data is
visible in the Specification dialog; generic (extends the UML `Element` metaclass).

## Mechanism — VERIFIED (this is exactly how UAF itself ships)

1. **Profile file** lives in `<install>\profiles\<Name>.mdzip`. Confirmed: the install's
   `profiles\` holds `UAF Profile.mdzip`, `SoaML Profile.mdzip`, plus dedicated
   `* Customization.mdzip` files (the invisible-stereotype + Specification mechanism).
2. **Resource descriptor** `<install>\data\resourcemanager\MDR_Plugin_<name>_<id>_descriptor.xml`
   declares the install via a flat copy manifest:
   `<installation><file from="profiles/X.mdzip" to="profiles/X.mdzip"/></installation>`
   (`to` always equals `from`; no `installRoot`; `to=profiles/...` lands in
   `<install>\profiles\`). Copy the shape from `MDR_Plugin_Testing Profile_1337_descriptor.xml`.
   Pick an unused numeric `id`.
3. **Auto-attach into EVERY project** (no user mount, no runtime `useModule`): a per-plugin
   `<install>\plugins\<pluginId>\mandatory.profiles` text file listing the profile BY
   MODEL NAME, one per line. Verified references:
   - `plugins\com.nomagic.uaf\mandatory.profiles` = `UAF Profile` + `UAF_Customization`
   - `plugins\com.nomagic.magicdraw.sysml\mandatory.profiles` = `MD_customization_for_SysML`
   `StandardProfilesHelper.findLoadModuleOnDemand(...)` attaches these at project load.
4. **APIs verified** (javap): `ApplicationEnvironment.getProfilesDirectory()`,
   `StereotypesHelper.isInvisible(RedefinableElement)`,
   `ProjectsManager.useModule(Project, ProjectDescriptor)`,
   `ProjectDescriptorsFactory.createProjectDescriptor(URI)`,
   `ModulesService.setStandardSystemProfile(IProject, boolean)`,
   `ModulesService.setAutoLoadKind(..., AutoLoadKind.ALWAYS_LOAD)`.
5. **Invisibility** = authored into the profile: stereotype with NO icon + a Customization
   with `hideMetatype=true` (label never drawn) + no shape/DSL keys. `isInvisible(...)`
   asserts it. Customization ships as a separate `<Name> Customization.mdzip` that targets
   the stereotype and exposes the tags in the Specification (mirrors `UAF_Customization`).

## Root cause of the earlier "Module not found"

The deployed plugin dir held BOTH `Semantic Alignment Profile.mdzip` AND a
`Semantic%20Alignment%20Profile.mdzip` URI-encoded twin, in the WRONG location
(`<plugin>\profiles\` not `<install>\profiles\`), and the file was a per-project scratch
`exportModule` whose internal references point at its origin project's UML Standard
Profile → unresolvable elsewhere. Mounting must be from `<install>\profiles\` (shared
standard-profile ID space) or, better, via `mandatory.profiles` (no mount at all).

## What's set up so far

- Copied `Semantic Alignment Profile.mdzip` → `E:\Magic SW\CMSoS26xR1pr\profiles\`.
- Created `E:\Magic SW\CMSoS26xR1pr\plugins\com.nomagic.magicdraw.plugins.semantic\mandatory.profiles`
  = `Semantic Alignment Profile`.

## The BLOCKER (needs owner input)

The current `.mdzip` (from programmatic `exportModule`) did NOT auto-attach when opening a
saved UAF sample, and I could not cleanly test a NEW project (harness `createProject()`
returns null — Cameo's new-project flow doesn't complete under the automated probe). Two
likely causes, both hard to iterate on blindly:
1. The module is not flagged a **standard/system profile**, so `mandatory.profiles` skips
   it (research-flagged; `setStandardSystemProfile` exists but authoring it programmatically
   is unproven).
2. Auto-attach may only apply to NEW projects, not already-saved ones (needs a clean new-
   project test I can't script reliably).

**Fastest reliable path**: author the profile ONCE in the Cameo GUI (File > New Project >
Profile; add invisible `SemanticAlignment` stereotype extending `Element` with tags
mappedConceptURI/ontologySource/mappingConfidence; add the Customization; save as a proper
shared/standard profile to `profiles\`). Then the plugin wiring (descriptor +
mandatory.profiles + code cleanup + verification) is straightforward and I can complete it.
An expert can do this in a few minutes; alternatively I can drive it via teach mode.

## Customization mechanism — LEARNED from the shipped profiles (live inspection)

The `«Customization»` stereotype is `UML Standard Profile::MagicDraw Profile::DSL
Customization::Customization` (always available; part of the UML Standard Profile). To
make a stereotype invisible-on-diagrams but Specification-configurable, create a
Customization element (a Class with `«Customization»` applied) targeting the stereotype.
Real values observed on shipped customizations (SysML TestCase/BusinessRequirement/Copy):

- `customizationTarget : Class` = **the target stereotype** (e.g. SemanticAlignment)
- `hideMetatype : boolean` = **true**  → the `«Stereotype»` label is NOT drawn = invisible
- `representationText : String` = display name (e.g. "Semantic Alignment")
- `category : String` = the Specification tab/group name
- `standardExpertConfiguration : String[]` = HTML fragments controlling Specification
  property visibility per property: `<html><head><title>SPF</title></head><body><p>NAME</p></body></html>`
  where the title code = Show/Hide × Property/section: `SP`/`SPF`=show property, `HP`/`HPF`
  =hide property, `SN`/`EN`=show/expand section. To show only our 3 tags: emit `SPF` for
  mappedConceptURI/ontologySource/mappingConfidence and `HPF` for the noise.
- Other useful: `hiddenOwnedTypes`, `possibleOwners`, `helpID`, `checkSpelling`,
  `showPropertiesWhenNotApplied`.

`StereotypesHelper.isInvisible(Stereotype)` is a separate flag (10/1517 UAF stereotypes
are invisible, e.g. view/package stereotypes) — but for "no label when applied to a user
element", `hideMetatype=true` on the Customization is the operative mechanism.

This is authorable programmatically: apply `«Customization»` to a Class in the profile
and set these tagged values. No GUI step strictly required for the customization itself.

## Plugin code cleanup queued (once the profile is valid)

- Delete `StereotypeManager.instantiateShippedProfile` + `dismissNewDialogs` + the
  watcher/mount machinery + `setShippedProfile`; `ensureProfileAvailable` becomes a thin
  fallback that mounts from `getProfilesDirectory()` (mandatory.profiles is the primary).
- `SemanticAlignmentPlugin`: drop the plugin-dir profile path.
- `build.gradle`: stop copying the profile into `<plugin>\profiles\`; add `deployProfileAsset`
  to place the profile + customization + descriptor + mandatory.profiles into the install.
- Remove the stale `%20` twin from the deployed plugin dir.
