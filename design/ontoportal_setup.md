# OntoPortal Sources — End-User Setup (2026-07-07)

The plugin can federate **OntoPortal** ontology registries alongside the always-on EBI OLS4 +
TIB engineering terminology. One adapter covers every OntoPortal instance; each is enabled
**only when you supply its free API key**. This unlocks, for the "Search Online Ontologies"
button:

| Portal | Domains it adds | Register for an account + API key |
|---|---|---|
| **BioPortal** | medical devices, clinical, ~1000 ontologies (incl. license-gated SNOMED/MedDRA, flagged automatically) | https://bioportal.bioontology.org/accounts/new |
| **IndustryPortal** | manufacturing, Industry 4.0, systems engineering (hosts CCO, IOF, BFO, MASON) | https://industryportal.enit.fr |
| **MatPortal** | materials science | https://matportal.org |

## 1. Get a free API key (per portal you want)

1. Open the portal's site (links above) and create a free account.
2. After logging in, open your **Account** page — the **API Key** is shown there
   (a long hex string). Copy it.
3. Repeat for each portal you want to enable. You only need the ones relevant to your work.

## 2. Configure the key(s)

Create a plain-text file at:

```
C:\Users\<you>\.semantic_alignment_plugin\ontoportal.properties      (Windows)
~/.semantic_alignment_plugin/ontoportal.properties                   (macOS/Linux)
```

with one line per portal you enabled:

```properties
# Only include the portals you registered for. Restart CATIA Magic after editing.
bioportal.apikey=PASTE_YOUR_BIOPORTAL_KEY_HERE
industryportal.apikey=PASTE_YOUR_INDUSTRYPORTAL_KEY_HERE
matportal.apikey=PASTE_YOUR_MATPORTAL_KEY_HERE
```

Restart CATIA Magic. Each portal with a key becomes part of "Search Online Ontologies"
(results merge + de-duplicate across OLS4, TIB, and every configured portal).

## 3. Optional tuning

Per portal you may add:

```properties
# Scope a portal to specific ontologies (comma-separated acronyms) for higher precision:
bioportal.ontologies=SNOMEDCT,RISKMAN
industryportal.ontologies=IOF,CCO,MASON
# Override the API base URL if a portal returns nothing (defaults below are pre-set):
#   bioportal      https://data.bioontology.org      (confirmed)
#   industryportal https://industryportal.enit.fr
#   matportal      https://matportal.org
industryportal.url=https://industryportal.enit.fr
```

A `-Dsemantic.plugin.ontoportal.<portal>.<property>` JVM system property overrides the file
if you prefer setting it in Cameo's launch options.

## Notes

- **No key = portal simply off.** With no `ontoportal.properties`, only OLS4 + TIB are queried
  (unchanged behavior). Nothing breaks.
- **Restricted licenses are flagged, not blocked.** SNOMED CT, MedDRA, LOINC, ICD, GMDN, MeSH
  hits show a license note (owner policy: notify, never redistribute). You may reference them
  remotely, but confirm your license before storing/redistributing those terms.
- **IndustryPortal TLS:** the IndustryPortal host has, at times, presented an expired
  certificate. If IndustryPortal returns nothing, verify the current API host with the portal
  operator and set `industryportal.url` accordingly.
- The key file lives outside the project and outside the Cameo install, and is **never
  committed to git**.
