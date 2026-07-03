# Tutorial: How to Use the Generated UAF Ontology

This tutorial explains how to use the generated UAF OWL 2 DL ontology to model enterprise architectures, execute reasoners, and check for logical consistency.

*   For a comparative report on foundational upper ontologies (gUFO, BFO, DOLCE, IDEAS 4D) and how UAF concepts map to them, see [Foundational Ontologies in Engineering](file:///e:/_Documents/git/UAF_Ontology/tutorial/FOUNDATIONAL_ONTOLOGIES.md).

---


## 1. Importing the Ontology
In your ontology editor (such as Protégé) or RDF/OWL library (such as Apache Jena or rdflib):
1. Load [uaf_ontology.ttl](file:///e:/_Documents/git/UAF_Ontology/UAF%20to%20OWL%20Goals/ontologies/uaf_ontology.ttl).
2. The import statement `owl:imports <http://purl.org/nemo/gufo#>` will pull in the **gUFO foundational ontology** automatically. (Note: The trailing hash `#` is required for Visual Ontology Modeler (VOM) compatibility, as VOM registers the gUFO package with the IRI `http://purl.org/nemo/gufo#` in its internal ontology index). Keep `gufo.ttl` in the same directory for offline resolution.


---

## 2. Instantiating Model Elements (ABox)
To model actual architecture instances, define individuals belonging to the generated classes.

### Example: Modeling a Capability and a Resource
```turtle
@prefix uaf: <http://purl.org/uaf/ontology#> .
@prefix ex: <http://example.org/my-architecture#> .

# Define an actual System
ex:SatelliteSystem_Alpha rdf:type owl:NamedIndividual , uaf:System ;
    rdfs:label "Satellite System Alpha" .

# Define an actual Capability
ex:SecureCommunication rdf:type owl:NamedIndividual , uaf:Capability ;
    rdfs:label "Secure Communication Capability" .

# Relate the System to the Capability it exhibits
ex:SatelliteSystem_Alpha uaf:exhibitsCapability ex:SecureCommunication .
```

---

## 3. Handling Mixin Classes (Solution 2)
Classes like `MeasurableElement`, `CapableElement`, and `UAFElement` are defined as mixin classes:
* They have no direct `rdfs:subClassOf gufo:Object` or `gufo:Event` restrictions.
* When creating an individual of `System` (which is a subclass of `MeasurableElement` and `gufo:Object`), the reasoner will automatically infer that the individual is a `gufo:Object`.
* When creating an individual of `OperationalActivity` (which is a subclass of `MeasurableElement` and `gufo:Event`), the reasoner will automatically infer that the individual is a `gufo:Event`.

---

## 4. Running a Reasoner ( Pellet / HermiT )
To validate model consistency:
1. Open the project in **Protégé**.
2. Go to `Reasoning` $\rightarrow$ Select `Pellet` or `HermiT`.
3. Click `Start Reasoner`.
4. If there are disjointness violations (e.g. an individual asserted as both a `Resource` and an `OperationalActivity`), the reasoner will display an inconsistency error.

---

## 5. Importing into Cameo Concept Modeler (CCM)

When importing `uaf_ontology.ttl` into Cameo Concept Modeler (CCM), follow these guidelines to ensure proper setup and to understand the import warnings.

### 5.1 Recommended Project Setup
Since the UAF ontology imports **gUFO** (`owl:imports <http://purl.org/nemo/gufo>`), always initialize your Cameo project using the **`ConceptModelinggUFO` template** (or attach the `gUFO.mdzip` project usage/library manually via **File** $\rightarrow$ **Use Project...**). This ensures all imported gUFO concepts are resolved locally.

### 5.2 Understanding Import Warnings
During import, Cameo's console may print warning logs like `Creating Anything 'Thing'` and `Assuming domain of 'owl:Thing'`. These warnings are completely normal and are caused by a fundamental **UML vs. OWL metamodel mismatch**:

*   **First-Class Properties (OWL):** In OWL, properties (including annotation properties like `dc:creator` or `rdfs:label`) do not require a restricted domain and can float independently.
*   **Class-Owned Properties (UML):** In UML, properties (attributes and associations) cannot exist independently; they must be owned by a containing Class.

#### How CCM Bridges This Gap:
For any property that lacks an explicit class domain, CCM falls back to UML's root class `Thing` (corresponding to `owl:Thing`). It automatically creates a `Thing` class placeholder in the containment tree to own the property, logging:
1. `Creating Anything 'Thing' for '<namespace>'`
2. `Assuming domain of 'owl:Thing' for property '...'`

These warnings are informational diagnostic logs and do not prevent a successful import. All **435 concepts** and **272 properties** will still map correctly to UML.

