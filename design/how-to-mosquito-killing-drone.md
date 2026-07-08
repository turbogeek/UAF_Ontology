# How to create the **Mosquito‑Killing Drone** compound concept

A worked, click‑by‑click guide to the **Compose Concept** composer. By the end you'll have an
element whose meaning reads, in SBVR:

> **A Mosquito Killing Drone is a Drone that kills a Mosquito.**

…grounded in real ontology concepts (the mosquito comes from the online malaria ontology), stored
on the element, and exported to RDF.

---

## Before you start

- **An element to describe.** Create (or pick) a Class named `MosquitoKillingDrone` in your model
  tree. The composer uses the element's name as the new concept's name.
- **The model is instrumented.** If it isn't, the plugin offers to instrument it the first time
  you apply (adds the Semantic Alignment profile + a model‑level ontology root IRI). Say *Yes*.
- **The catalog service is running.** It auto‑starts with the plugin. Online search (for the
  mosquito) needs internet access; local concepts work offline.

---

> **You can work in any order.** The composer doesn't force "genus first." Search for whatever you
> think of first — `Mosquito`, `Drone`, anything — add it, and sort out which piece is the genus and
> which are relations afterward. The steps below are just *one* path to the same result.

## Step 1 — Select the element

In the containment tree, click **`MosquitoKillingDrone`**. The sidebar's suggestion list fills with
concepts that match the name — you can ignore those for now.

## Step 2 — Open the composer

In the sidebar, click **`Compose Concept…`**. The **Compose Concept** dialog opens. The name field
at the top already says `MosquitoKillingDrone` — leave it. **online** is ticked by default, so search
already reaches the online ontologies; untick it only if you want offline-only results.

## Step 3 — Find your concepts (search in any order)

1. In **1. Search ontologies for concepts**, type `Mosquito` and click **Search**. You'll get several
   real concepts — e.g. **`Mosquito`** and **`Mosquito Control`** (this is why search lives in the
   dialog: you pick the exact one you mean). Click a result to read its meaning in **What the selected
   concept means**.
2. Search `drone` next. *(A drone isn't in the offline catalog; the online sources have it. Prefer an
   offline, foundational grounding? Search `aircraft` — a drone is a kind of aircraft.)*
3. **Missing a concept entirely?** Click **`New concept…`**, type its name and an optional definition,
   and it's minted in *this model's* namespace and added to the results — ready to use like any other.

## Step 4 — Assemble the compound

Now say which concept is the **genus** (what it fundamentally *is*) and which are **relations** (what
makes it special):

- Select **`Drone`** and click **`Set as genus (is a kind of)`** — the builder header reads
  *…is a kind of **Drone***.
- Select **`Mosquito`** and click **`Add as → relation`** — a row appears: `that [ has function ▾ ]
  Mosquito`.

Got them backwards? Every relation row has a **`genus`** button that promotes it to the genus (the old
genus drops back down to a relation), plus **↑ / ↓** to reorder and **✕** to remove. So even if you
added Mosquito as the genus first, one click fixes it.

## Step 5 — Choose the relation

In the Mosquito row, open the relation dropdown and pick **`kills`**. *(The list is editable — type
your own verb phrase, e.g. "suppresses".)* Need a verb that isn't listed at all? Click
**`New relation…`**, type the phrase, and it's added to every row's dropdown.

## Step 6 — Read the live SBVR

The **SBVR of the concept you are creating** pane updates as you go and now reads:

> **A Mosquito Killing Drone is a Drone that kills a Mosquito.**

Add more relations the same way (e.g. `New concept…` → `Sprayer`, *Add as → relation* → `uses`), and
each appears in the sentence: *"…that kills a Mosquito and uses a Sprayer."*

## Step 7 — Apply

Click **`Apply Compound Concept`**. The composer closes and the compound is written onto the
element.

---

## What you just created

**On the element** (as the multi‑valued `mappedConceptURI` tag):

| Slot | Value |
|---|---|
| genus | the **Drone** IRI |
| differentia | `kills \| <mosquito IRI> \| Mosquito` |

**In the exported ontology** (Turtle):

```turtle
:MosquitoKillingDrone  rdf:type  <…#Drone> ;
                       rdfs:label "MosquitoKillingDrone" ;
                       project:kills  obo:IDOMAL_0000746 ;      # the real malaria-ontology mosquito
                       skos:definition "A Mosquito Killing Drone is a Drone that kills a Mosquito." .
project:kills  rdfs:label "kills" .
```

The mosquito points at the actual ontology concept, so a reasoner or another modeler gets the real
meaning — not just the words.

---

## Tips & variations

- **Not sure which concept?** Select each result and read its definition before adding — that's the
  whole point of having search inside the dialog.
- **Offline / grounded genus.** Search `aircraft` (SUMO/CCO, offline) for the genus if you can't
  reach the internet or want a foundational grounding; the sentence becomes *"…is an Aircraft
  that kills a Mosquito."*
- **Better verbs.** The relation dropdown is a curated, editable list (`kills`, `targets`,
  `has function`, `is part of`, …). Add site‑specific verbs by dropping a `relations.txt` in the
  deployed plugin folder.
- **Change your mind.** Re‑open **Compose Concept** on the element any time; **Apply** replaces the
  previous compound.
