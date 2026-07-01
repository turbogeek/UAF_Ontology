# Ontology Design Decisions: Resolving UML-to-OWL Disjointness Conflicts

This document records the architectural decision to resolve disjointness conflicts between static objects/aspects (endurants) and behavioral events/processes (occurrents) when translating the UAF Domain Metamodel (DMM) to OWL 2 DL.

---

## 1. The Core Duality Problem
In the UAF DMM UML profile, abstract classes like `InteractionScenario` and `ProcessParameter` inherit from multiple base classes:
* **Structural/Static Base:** `MeasurableElement` (allows the element to have performance metrics/properties).
* **Behavioral/Occurrent Base:** `UML2.5Metamodel::Interaction`, `UML2.5Metamodel::Activity`, or `BPMN2Metamodel::Process` (defines execution and control flow).

In formal foundational ontologies like **gUFO**, `gufo:Object` and `gufo:Event` are strictly **disjoint**. An entity in OWL 2 DL cannot inherit from both.

---

## 2. Selected Strategy: Solution 2 (The Mixin Pattern)
We have implemented **Solution 2 (The Mixin Pattern)**.

### How It Works:
* Abstract capability/utility metaclasses (`MeasurableElement`, `CapableElement`, `SubjectOfOperationalConstraint`, etc.) are mapped to OWL as **plain classes** without any direct subclass assertion to `gufo:Object` or `gufo:Event`.
* Any external metamodel classes (e.g., classes from `UML2.5Metamodel::` or `BPMN2Metamodel::` namespaces) are also treated as mixins.
* Concrete UAF concepts inherit from their respective mixins, but only receive a `gufo:Object` or `gufo:Event` classification from their UAF-specific domain superclasses.

### Pros:
* Preserves 100% of the UAF standard's class hierarchy.
* Resolves all OWL DL reasoning conflicts.

---

## 3. Fallback Strategies (In Case of Reasoning Issues)
If future modeling tools or strict reasoners encounter issues with mixins, we will fallback to:

### Fallback A: Solution 1 (Relationship Decoupling)
Remove the inheritance generalization `InteractionScenario rdfs:subClassOf MeasurableElement` and replace it with an object property:
```turtle
uaf:InteractionScenario rdfs:subClassOf gufo:Event .
uaf:hasMeasurement rdfs:domain uaf:InteractionScenario ;
                   rdfs:range uaf:MeasurableElement .
```

### Fallback B: Solution 3 (4D Spatiotemporal Interpretation)
Adopt a 4D ontology foundation (like the IDEAS Group or BORO ontology) where both objects and events are represented as 4D spatiotemporal extents, removing the 3D endurant/occurrent disjointness constraint.

---

## 4. UAF Grid Layout Mapping (Aspects and Viewpoints)
The Unified Architecture Framework organizes architectural views into a matrix grid:
* **Rows (Aspects):** Strategic, Operational, Services, Personnel, Resources, Security, Projects, Standards, Actuals, Metadata, etc.
* **Columns (Viewpoints):** Taxonomy, Structure, Connectivity, Processes, States, Sequences, Information, Parametric, Constraints, Roadmap, Traceability.

### Mapping Implementation:
We extract this grid mapping dynamically by walking up the XMI package nesting tree (`uml:Package`) for each DMM element:
* The first containing package matching a UAF Column name is assigned as `uaf:gridViewpoint`.
* The next containing parent package matching a UAF Row name is assigned as `uaf:gridAspect`.
* These values are written to the ontology as `owl:AnnotationProperty` metadata, enabling modeling tools (like Cameo) to automatically lay out elements in their correct viewpoints.

---

## 5. Metaconstraints Enforcement
In the UAF profile, **metaconstraints** specify rules for which stereotypes can connect to which other stereotypes.

### Mapping Implementation:
In OWL, metaconstraints are natively and formally enforced through the use of:
* `rdfs:domain` and `rdfs:range` on OWL object properties (restricting what types of elements can be connected by a relationship).
* `owl:Restriction` axioms on classes (e.g. enforcing that a `Resource` must exhibit at least one `Capability`).
* These logical rules provide stronger verification than simple UML profiling, allowing description logic reasoners to automatically find modeling errors.

