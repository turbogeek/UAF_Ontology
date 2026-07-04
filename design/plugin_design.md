# CATIA Magic / Cameo Semantic Integration Plugin Design Specification

This document details the software design, OpenAPI integrations, and validation workflows for the **UAF/SysML-to-Ontology Semantic Integration Plugin** inside CATIA Magic (Cameo Systems Modeler).

---

## 1. System Architecture & OpenAPI Classes

The plugin is implemented as a Java-based add-on running inside Cameo's JVM. It hooks into Cameo’s desktop GUI and EMF (Eclipse Modeling Framework) model store:

```mermaid
graph TD
    classDef default fill:#1a1a2e,stroke:#3a3a5e,stroke-width:2px,color:#ccc;
    classDef primary fill:#4E79A7,stroke:#4E79A7,stroke-width:2px,color:#fff;

    Cameo[CATIA Magic / Cameo Desktop] -->|Java OpenAPI| Plugin[Semantic Integration Plugin]
    Plugin -->|Embedded JFXPanel| SidebarPanel[HTML5/JS Sidebar Panel]
    Plugin -->|EMF API| ModelStore[Cameo Model Store]
    Plugin -->|SPARQL / HTTP| RDFStore[RDF Graph Database / GraphDB]
    Plugin -->|REST API| BioPortal[BioPortal Ontology Registry]
```

### Key Cameo OpenAPI Hooks
1.  **Plugin Lifecycle (`com.nomagic.magicdraw.plugins.Plugin`):**
    *   Main entry point. Initializes the plugin configuration, registers listeners, and sets up UI menus.
2.  **Sidebar Docking (`com.nomagic.magicdraw.ui.FrameDescriptor`):**
    *   Creates a dockable sidebar panel in the Cameo user interface (`com.nomagic.magicdraw.ui.browser.BrowserTab`).
    *   Uses JavaFX `JFXPanel` or Chromium-embedded framework `CefBrowser` to render a modern, responsive HTML/JS front-end.
3.  **Model Event Listening (`com.nomagic.magicdraw.uml.symbols.PresentationElement`):**
    *   Hooks into selection events. When a user clicks a node (e.g. Block, OperationalPerformer) in the Cameo diagram, a listener gets its EMF representation (`org.eclipse.emf.ecore.EObject`).
4.  **UML/SysML Metamodel Access (`com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element`):**
    *   Traverses the parent-child containment hierarchy and extracts stereotype metadata (UAFML stereotypes or SysML v2 definitions).

---

## 2. Guided Modeling Tutorials

To validate the plugin, three guided modeling tasks of increasing complexity are defined:

```mermaid
graph LR
    classDef default fill:#1a1a2e,stroke:#3a3a5e,stroke-width:2px,color:#ccc;
    
    Simple[1. Simple Org Model] -->|Extend to Chemistry| Evolved[2. Evolved EV Battery]
    Evolved -->|Scale to Combat Operations| Hoth[3. Complex Battle of Hoth]
```

### Tutorial A: Simple Organizational Structure (Simple Model)
*   **Modeling Objective:** Model an organizational department and its reporting line.
*   **Step-by-step in Cameo:**
    1.  Create an `ActualOrganization` block named `ResearchDivision`.
    2.  Create a nested `ActualOrganization` named `BatteryDesignTeam`.
    3.  Create an `ActualPost` inside the team named `LeadBatteryChemist`.
    4.  Draw a `hasPost` composition relation from the team to the chemist post.
*   **Plugin Semantic Mapping:**
    *   The sidebar automatically searches the W3C ORG ontology.
    *   User maps `ResearchDivision` to `org:Organization`.
    *   User maps `BatteryDesignTeam` to `org:OrganizationalUnit`.
    *   User maps `LeadBatteryChemist` to `org:Post`.
    *   **Resulting SBVR:** `Instance: LeadBatteryChemist is a Post owned by BatteryDesignTeam.`

### Tutorial B: Evolved EV Power Subsystem (Evolved Model)
*   **Modeling Objective:** Model battery cell modules and chemistry details.
*   **Step-by-step in Cameo:**
    1.  Create a block `HighVoltageBatteryPack`.
    2.  Create sub-parts `Module1` and `Cell1` connected via composition.
    3.  Define stereotype properties `chemistry = "LithiumSulfur"` and `voltage = 3.7V`.
*   **Plugin Semantic Mapping:**
    *   The plugin looks up EMMO/BattINFO.
    *   User maps `HighVoltageBatteryPack` to `battinfo:BatteryPack`.
    *   User maps `Cell1` to `battinfo:BatteryCell`.
    *   **Ontology Extension Wizard:** Since "Lithium Sulfur chemistry" has specific parameters not fully captured in the baseline, the wizard creates a custom subclass `ev:LithiumSulfurCell` under `battinfo:BatteryCell` and pushes it to the triplestore.

### Tutorial C: The Battle of Hoth (Highly Complex Model)
*   **Modeling Objective:** Model the massive tactical operations, capabilities, and resources of the Rebel Alliance defend-and-evacuation mission on Hoth against the Galactic Empire's assault (derived from Matthew Hause's UAF Battle of Hoth methodology).
*   **Step-by-step in Cameo:**
    1.  **Operational View (Logical):**
        *   Model `EchoBase` as an `OperationalPerformer`.
        *   Model `OperationalActivity`: `DefendEchoBase`, `EvacuateTransportShips`.
        *   Define `OperationalMessage` flow between `IonCannonControl` and `TransportShip`.
    2.  **Resource View (Physical):**
        *   Model `ImperialAssaultForces` containing `AT-AT_Walker` and `Stormtroopers` as `ResourcePerformer` nodes.
        *   Model `RebelDefenseForces` containing `V-47_Snowspeeder` and `DF.9_LaserTurret` resource nodes.
    3.  **Strategic View (Capabilities):**
        *   Define `EnterpriseGoal`: `SecureAllianceEvacuation`.
        *   Define `Capability`: `HeavyGroundAssault` (held by AT-AT) and `AirInterception` (held by Snowspeeder).
*   **Plugin Semantic Mapping:**
    *   **SUMO Military Ontology:** Maps `ImperialAssaultForces` to `sumo:MilitaryUnit`.
    *   **SUMO Vehicle Ontology:** Maps `AT-AT_Walker` to `sumo:LandVehicle` and `sumo:WeaponSystem`.
    *   **OMG BMM:** Maps the mission goal `SecureAllianceEvacuation` to `bmm:Goal`.
    *   **Logical Validation:** The reasoner checks for tactical capability gaps. For example, if a `Capability` is modeled without any `ResourcePerformer` possessing it, the validation panel flags an error.

---

## 3. Interactive User Interface Mockup

To visualize the plugin interface within CATIA Magic / Cameo Systems Modeler, an interactive mockup has been built. You can open and view it locally at [cameo_plugin_mockup.html](file:///e:/_Documents/git/UAF_Ontology/cameo_plugin_mockup.html).

Below is the recorded session showing the plugin’s interactive diagram selections, autocomplete mapping search, and reasoner audit animation:

![Cameo Plugin Mockup Interface Demo](/C:/Users/DBR2/.gemini/antigravity-ide/brain/d8a2612a-bf9d-47d3-ab1b-5fb67cbdf0f1/cameo_plugin_interface_demo_1783202851288.webp)

---

## 4. Formal Testable Requirements

To ensure the plugin operates correctly and predictably across both legacy and modern modeling environments, it is developed against the following testable requirements:

| Requirement ID | Requirement Name | Description | Verification Method |
| :--- | :--- | :--- | :--- |
| **PLG-REQ-01** | UML Model Traversal | The plugin must extract all stereotypes, properties, and relationships from standard UML/UAFML projects. | Automated Integration Test |
| **PLG-REQ-02** | KerML/SysML v2 Traversal | The plugin must parse SysML v2 definitions, usages, and composition structures from the Cameo model store. | Automated Integration Test |
| **PLG-REQ-03** | Multi-Ontology Alignment | The plugin must support binding model nodes to SUMO, BMM, ORG, and EMMO/BattINFO namespaces. | Unit Test / Reasoner |
| **PLG-REQ-04** | SBVR Sentence Generation | On selection of a node, the plugin must generate valid SBVR Structured English markup and render it in HTML. | GUI Unit Test |
| **PLG-REQ-05** | Embedded SHACL Auditing | The plugin must execute SHACL validation rules over the exported model graph and flag compliance failures. | Automated Integration Test |
| **PLG-REQ-06** | HermiT DL Reasoning | The plugin must trigger local HermiT DL reasoning check to confirm ontology and model consistency. | Reasoner Test |

---

## 5. Test-Driven Development (TDD) Methodology

The plugin follows a strict **Test-First (TDD) Iterative Methodology**, ensuring that every extraction, mapping, and validation feature is backed by a failing test before implementation:

```
[Write Scenario Test] ──> (Test Fails: RED) ──> [Write Minimal Parser Code] ──> (Test Passes: GREEN) ──> [Refactor Code]
```

### Iterative Lifecycle
1.  **Iterative Phase 1 (Core Model Parsing):** Focuses on PLG-REQ-01 and PLG-REQ-02. Unit tests are written to verify that EMF objects are parsed and mapped to RDF triples.
2.  **Iterative Phase 2 (Semantic Reasoning & SHACL):** Focuses on PLG-REQ-05 and PLG-REQ-06. Tests run headless model assemblies and verify that disjointness axioms and SHACL compliance failures are caught.
3.  **Iterative Phase 3 (Interactive GUI Panel):** Focuses on PLG-REQ-04. Tests simulate mouse clicks on nodes, verifying that the JFXPanel updates the SBVR rendering correctly.

---

## 6. Embedded Test Harness Architecture

To enable 100% automated scenario testing, the plugin includes a headless **Embedded Test Harness** that launches a virtual MagicDraw/Cameo session, loads test model ZIP files, constructs elements, and validates logical consistency.

### Headless Test Execution Architecture

```
JUnit Runner ──> Cameo Headless Instance ──> Load Model (e.g. Battle of Hoth) ──> Export to RDF ──> Run HermiT & SHACL ──> Assert Validation Output
```

### Mock Test Harness Implementation (Java / JUnit)
The following JUnit code shows how the test harness automatically executes scenario validations for building complete models (such as the Sounding Rocket) and running SHACL shapes checks:

```java
package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.tests.MagicDrawTestCase;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.InstanceSpecification;
import org.junit.Before;
import org.junit.Test;
import java.io.File;

public class SemanticValidationTestHarness extends MagicDrawTestCase {

    private Project testProject;
    private SemanticRDFExporter exporter;
    private SHACLValidator validator;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Initialize the headless Cameo application instance
        Application app = Application.getInstance();
        File projectFile = new File("src/test/resources/models/SoundingRocketTest.mdzip");
        app.getProjectsManager().loadProject(projectFile, false);
        this.testProject = app.getProject();
        this.exporter = new SemanticRDFExporter(this.testProject);
        this.validator = new SHACLValidator();
    }

    @Test
    public void testSoundingRocketSemanticConsistency() throws Exception {
        // Step 1: Export the current model to RDF Triples
        String rdfTriples = exporter.exportToTurtleString();
        assertNotNull("RDF Export string should not be null", rdfTriples);
        assertTrue("RDF should contain the Sounding Rocket instance", rdfTriples.contains("sr:inst-sounding_rocket"));

        // Step 2: Validate the model using the embedded HermiT DL Reasoner
        ReasonerResult reasonerResult = validator.runHermitReasoner(rdfTriples);
        assertTrue("Model must be logically consistent", reasonerResult.isConsistent());

        // Step 3: Run SHACL Validation (verifying requirement satisfiability)
        SHACLAuditReport shaclReport = validator.runSHACLAudit(rdfTriples, "ontologies/uaf_traceability_shapes.ttl");
        assertTrue("SHACL Validation must pass with zero violations", shaclReport.getViolationsCount() == 0);
    }
}
```

This test harness is fully integrated into the plugin's Continuous Integration (CI) configuration, enabling human architects and LLM coding assistants to query validation health and run automatic lint checks via a simple command line interface.

---

## 7. Enterprise Development Standards & Core Architecture Principles

To maintain high maintainability, code quality, and robustness inside CATIA Magic, the plugin must adhere to the following software engineering standards:

### 7.1. CATIA Magic / Cameo OpenAPI Best Practices
*   **Transaction Safety via Sessions:** Any write operation modifying model elements, stereotypes, or properties must be wrapped in an explicit Cameo session. Transactions must follow this exact safe wrapper structure:
    ```java
    SessionManager.getInstance().createSession(project, "Map Semantic Concept");
    try {
        // Perform model modifications
        SessionManager.getInstance().closeSession(project);
    } catch (Exception e) {
        SessionManager.getInstance().cancelSession(project);
        Log.error("Failed to apply semantic mapping to model element", e);
    }
    ```
*   **Headless vs. Headful Duality:** The core extraction, parsing, and validation logic must be fully independent of the GUI. It must check `Application.getInstance().isHeadless()` and avoid launching any JavaFX or Swing frames if running in a command line CI/CD test environment.

### 7.2. UI/UX Thread Safety and Bridging
*   **JavaFX and Swing Thread Separation:** Cameo runs on the Swing Event Dispatch Thread (EDT), while the plugin sidebar utilizes JavaFX. 
    *   Any UI-modifying code for Swing components must be delegated using `javax.swing.SwingUtilities.invokeLater()`.
    *   Any UI-modifying code for JavaFX components (e.g. updating the SBVR view or search results) must be delegated using `javafx.application.Platform.runLater()`.
*   **Non-Blocking UI (Asynchronous Auditing):** Long-running reasoner audits or remote BioPortal API searches must run on separate background worker threads (using Java `SwingWorker` or JavaFX `Task`), displaying a progress spinner to ensure Cameo’s GUI remains fully responsive.

### 7.3. Core Clean Code Principles
*   **KISS (Keep It Simple, Stupid):** Do not build custom UI frameworks or heavy state stores. Leverage standard native Cameo frames, simple properties files, and basic JSON payloads.
*   **SOLID Principles:**
    *   *Single Responsibility (SRP):* Separate the RDF traversal engine (`SemanticRDFExporter`) from the reasoner execution engine (`HermitValidationEngine`).
    *   *Open-Closed (OCP):* Enable loading new domain ontologies dynamically by registering URI namespaces in config files without recompiling the parser classes.
    *   *Interface Segregation (ISP):* Define specialized small interfaces for exporter variants (e.g. `UMLModelExporter` vs. `KerMLModelExporter`).
*   **Separation of Concerns (SoC):** Model extraction (EMF), semantic alignment logic, and remote API networking must reside in distinct Java packages.
*   **Comment the "Why":** Comments must not restate what the code is doing. Instead, code comments must explain the engineering rationale, design decisions, dependency constraints, or specific UAF Grid/Requirements alignment:
    ```java
    // Under high ambient temperatures in hot showers/tubs (up to 40C), Peltier
    // efficiency drops. We enforce this holding range constraint to satisfy IC-REQ-001.
    if (ambientTemperature > 40.0) {
        triggerThermalAlarmBoundary();
    }
    ```

---

## 8. Security, Portability & Error Hardening

### 8.1. Platform Independence & Portability
*   **No Hard-coded Paths:** The code must not contain hard-coded drive letters, absolute directories, or OS-specific separators. Paths must be resolved dynamically relative to Cameo's installation folder:
    ```java
    // CORRECT: Resolving paths portably using Paths API and System Properties
    Path configPath = Paths.get(System.getProperty("user.home"), ".gemini", "config", "plugin.properties");
    ```
*   **Path Separator Independence:** Use `File.separator` or Java's `Paths` library rather than hard-coded `/` or `\\`.

### 8.2. Secrets and Credentials Security
*   **No Hard-coded Secrets:** Absolutely no passwords, API tokens (such as BioPortal keys), or triplestore endpoints may be written in the code.
*   **Configuration Injection:** Fetch credentials dynamically from a system environment variable (`System.getenv("BIOPORTAL_API_KEY")`) or an encrypted local properties file excluded from git source control.

### 8.3. Strict Input Sanitization
*   **RDF/Turtle Injection Protection:** Sanitize element names, labels, and property values before writing them out as triples. Strip out characters that could break RDF/XML formats or cause Turtle syntax errors (e.g., quotes, unescaped backslashes, XML special characters `<>&`).

### 8.4. Robust Exception Handling & Graceful Degradation
*   **Strict Error Boundaries:** Wrap all external network calls and reasoner invocations in global try-catch blocks to prevent Cameo desktop crashes.
*   **MD Logger Integration:** Catch and log all errors using Cameo's official logging facility:
    ```java
    import com.nomagic.utils.Log;
    ...
    Log.error("Reasoner execution encountered an exception during consistency check", e);
    ```
*   **Graceful Degradation:** If the remote BioPortal registry is offline, the plugin must gracefully degrade to use cached local OWL files, informing the user via an overlay warning instead of throwing unhandled exceptions.

---

## 9. Quality Assurance & Code Review Protocols

Before merging any change, the reviewer must check the code against the following rigorous pre-merge checklist:

### Pre-Merge Code Review Checklist

- [ ] **Transaction Safety:** Are all model modification operations wrapped inside a `SessionManager` try-catch-cancel block?
- [ ] **Thread Delegation:** Are JavaFX elements updated on the JavaFX thread (`Platform.runLater`) and Swing elements updated on the EDT (`SwingUtilities.invokeLater`)?
- [ ] **Platform Independence:** Are there any hard-coded absolute paths, drive letters, or system-specific separators?
- [ ] **Secrets Extraction:** Are there any hard-coded passwords, tokens, or local server endpoints in the source code?
- [ ] **Input Sanitization:** Are all names and values parsed from EMF sanitized before being exported to the Turtle/RDF graph?
- [ ] **Logging & Error Boundaries:** Do all external API calls have try-catch blocks that log errors to MagicDraw's `Log` class?
- [ ] **Requirements Tracing:** Are all newly added modules mapped to at least one testable requirement (e.g. `PLG-REQ-X`) via JavaDoc annotation?
- [ ] **TDD Validation:** Has a corresponding JUnit test case been written, executed, and passed under the headless test harness?



