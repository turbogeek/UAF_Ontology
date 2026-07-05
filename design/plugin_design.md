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

---

## 10. SBVR Translation Test Cases & Transaction Validation

### 10.1. SBVR Mapping Test Cases
The plugin’s SBVR generation engine must translate UML and SysML elements into structured English sentences. The table below defines the formal mapping scenarios that must be executed by the automated test suite:

| Scenario | UML / SysML Source Structure | Target Ontology Concept | Expected SBVR Output |
| :--- | :--- | :--- | :--- |
| **SC-01** | `Class` / `Block` named `EchoBase` | `sumo:MilitaryBase` | `Instance: EchoBase is a MilitaryBase.` |
| **SC-02** | Nested part `Module1` inside `BatteryPack` | `battinfo:BatteryModule` | `Instance: BatteryPack contains Module1.` |
| **SC-03** | Association from `Transmitter` to `Receiver` | `sr:connectedTo` | `Instance: Transmitter connected to Receiver.` |
| **SC-04** | `ActualPost` named `Chemist` in `DesignTeam` | `org:Post` | `Instance: Chemist is a Post owned by DesignTeam.` |
| **SC-05** | Generalization `AT-AT` specializes `LandVehicle` | `sumo:LandVehicle` | `Concept: AT-AT is a kind of LandVehicle.` |
| **SC-06** | `Requirement` satisfying strategic capability | `ic:refines` | `Instance: TempControlRequirement refines ActivePreservationCapability.` |
| **SC-07** | Org Unit conforming to ISO-9001 standard | `sr:conformsTo` | `Instance: PropulsionMfgTeam conforms to ISO 9001.` |
| **SC-08** | BMM Goal directing efforts to physical system | `bmm:Goal` | `Instance: PreventSpoilageGoal channels efforts towards InsulinCooler.` |

The JUnit code below shows how the test suite validates these SBVR translations against live elements:

```java
package com.nomagic.magicdraw.plugins.semantic.tests;

import org.junit.Test;
import static org.junit.Assert.*;

public class SBVRMappingTest extends MagicDrawTestCase {

    @Test
    public void testSBVRGenerationScenarios() {
        SBVREngine engine = new SBVREngine();

        // SC-01: Instantiation
        String sbvr1 = engine.generateSBVR("http://purl.org/uaf/example/ev_power#EchoBase", "sumo:MilitaryBase", null, null);
        assertEquals("Instance: EchoBase is a MilitaryBase.", stripHtml(sbvr1));

        // SC-02: Composition
        String sbvr2 = engine.generateSBVR("http://purl.org/uaf/example/ev_power#BatteryPack", "battinfo:BatteryModule", "contains", "Module1");
        assertEquals("Instance: BatteryPack contains Module1.", stripHtml(sbvr2));

        // SC-06: Requirement Refinement
        String sbvr3 = engine.generateSBVR("ic:TempControlRequirement", "ic:ActivePreservationCapability", "refines", null);
        assertEquals("Instance: TempControlRequirement refines ActivePreservationCapability.", stripHtml(sbvr3));
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", "").trim();
    }
}
```

### 10.2. Undo/Redo Transaction Verification
Any model modifications applied by the plugin must conform to Cameo's Swing undo command framework, ensuring no memory leaks or stale elements remain after an Undo command.

*   **Transaction Wrapper Design:**
    ```java
    package com.nomagic.magicdraw.plugins.semantic.commands;
    
    import com.nomagic.magicdraw.openapi.uml.SessionManager;
    import com.nomagic.magicdraw.core.Project;
    import com.nomagic.utils.Log;

    public class TransactionWrapper {
        public static void executeWrite(Project project, String sessionName, Runnable command) {
            SessionManager session = SessionManager.getInstance();
            session.createSession(project, sessionName);
            try {
                command.run();
                session.closeSession(project);
            } catch (Exception e) {
                session.cancelSession(project);
                Log.error("Semantic Mapping Transaction failed, changes rolled back.", e);
                throw new RuntimeException(e);
            }
        }
    }
    ```
*   **Verification Test Case:**
    ```java
    @Test
    public void testUndoRedoSemanticMapping() {
        Project project = Application.getInstance().getProject();
        Class cellElement = (Class) Finder.byName(project, "BatteryCell1A");
        
        // Assert initial state: no stereotype applied
        assertFalse(StereotypesHelper.hasStereotype(cellElement, "SemanticAlignment"));

        // Execute mapping transaction
        TransactionWrapper.executeWrite(project, "Apply Stereotype", () -> {
            StereotypesHelper.addStereotype(cellElement, getSemanticStereotype(project));
        });
        assertTrue(StereotypesHelper.hasStereotype(cellElement, "SemanticAlignment"));

        // Trigger Cameo Undo Command
        project.getUndoManager().undo();
        assertFalse("Model element should return to original unmapped state on Undo", 
                   StereotypesHelper.hasStereotype(cellElement, "SemanticAlignment"));

        // Trigger Cameo Redo Command
        project.getUndoManager().redo();
        assertTrue("Model element should re-apply mapping on Redo", 
                  StereotypesHelper.hasStereotype(cellElement, "SemanticAlignment"));
    }
    ```

---

## 11. Static Code Analysis & Java 12 Language Best Practices

### 11.1. Static Code Analysis (Java Linting)
To enforce coding compliance, the maven build pipeline binds Checkstyle, PMD, and SpotBugs validation checks:

*   **Checkstyle Integration (`pom.xml`):**
    ```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <version>3.1.2</version>
        <configuration>
            <configLocation>google_checks.xml</configLocation>
            <failOnViolation>true</failOnViolation>
            <violationSeverity>warning</violationSeverity>
        </configuration>
    </plugin>
    ```
*   **SpotBugs Null-Safety and Concurrency Checks:**
    Any potential null pointers in EMF tree traversals or multithreaded GUI operations must block compilation. Banned patterns include catching general `NullPointerException` or failing to release transaction lock resources.

### 11.2. Java 12 Best Practices
The plugin codebase uses Java 12 syntax improvements to maintain clean, performant, and modern structures:

*   **Switch Expressions (EMF Stereotype Classifier):**
    ```java
    public String resolveOntologyNamespace(String stereotypeName) {
        return switch (stereotypeName) {
            case "ActualOrganization", "ActualPost", "ActualPerson" -> "http://www.w3.org/ns/org#";
            case "Goal", "BusinessPolicy", "BusinessGoal"          -> "http://www.omg.org/spec/BMM/";
            case "Vehicle", "Device", "WeaponSystem"                 -> "http://www.ontologyportal.org/SUMO.owl#";
            case "Battery", "BatteryCell", "BatteryModule"           -> "https://w3id.org/emmo/domain/battery#";
            default -> "http://purl.org/uaf/ontology#";
        };
    }
    ```
*   **Compact Number Formatting (Telemetry Panels):**
    Formats rocket altitude meter measurements or battery capacities into human-readable compact layouts:
    ```java
    NumberFormat fmt = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
    String compactApogee = fmt.format(150000); // Renders as "150K"
    ```
*   **Teeing Collectors (Single-pass Model Auditing):**
    ```java
    public AuditReport runAudit(Stream<ModelElement> elements) {
        return elements.collect(
            Collectors.teeing(
                Collectors.filtering(el -> el.hasValidationIssues(), Collectors.toList()),
                Collectors.counting(),
                (issues, totalCount) -> new AuditReport(issues, totalCount)
            )
        );
    }
    ```

---

## 12. User Interface, Stereotype Tagging, and Integrated Help Systems

### 12.1. Plugin Project Activation Workflow
To enable the semantic validation features per-project:
1.  **Library Mounting:** The user mounts the `UAF Semantic Profile.mdzip` shared model library into their project directory.
2.  **Property Toggle:** The plugin adds a custom project property in Cameo's **Project Options** menu:
    ```
    Project Options -> Plugins -> Semantic Alignment -> Enable Auditing = True/False
    ```
3.  **Listener Registration:** If set to True, the plugin registers a model change listener (`com.nomagic.magicdraw.core.project.ProjectEventListener`) that monitors element additions and modifications in real time, feeding changes to the background audit thread.

### 12.2. Custom UML Stereotype Mapping (EMF/UML API)
Semantic mappings are stored inside the project files as UML Stereotypes using Cameo's programmatic stereotyping API:

```java
package com.nomagic.magicdraw.plugins.semantic.metadata;

import com.nomagic.magicdraw.uml.Finder;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

public class StereotypeManager {
    public static void applyMappingStereotype(Element element, String conceptURI) {
        Project project = Project.getProject(element);
        Stereotype stereotype = StereotypesHelper.getStereotype(project, "SemanticAlignment");
        
        if (stereotype == null) {
            throw new IllegalStateException("UAF Semantic Profile is not active for this project.");
        }
        
        // Apply stereotype and set tagged value mappedConceptURI
        StereotypesHelper.addStereotype(element, stereotype);
        StereotypesHelper.setStereotypePropertyValue(element, stereotype, "mappedConceptURI", conceptURI);
    }
}
```

### 12.3. Integrated Contextual Help System
The plugin includes a dedicated split pane at the bottom of the sidebar to assist modelers in choosing correct ontology concepts:
*   **Help Directory Structure:**
    ```
    help/
      ├── st-tx.md       # Help page explaining Strategic Capability Taxonomy (St-Tx)
      ├── op-tx.md       # Help page explaining Operational Performer Taxonomy (Op-Tx)
      └── battinfo.md    # Guide for EMMO Battery chemistry alignments
    ```
*   **Contextual Help Resolver Class:**
    ```java
    public class HelpResolver {
        public static String resolveHelpPage(Element selectedElement) {
            if (StereotypesHelper.hasStereotype(selectedElement, "Capability")) {
                return "help/st-tx.md";
            } else if (StereotypesHelper.hasStereotype(selectedElement, "ActualOrganization")) {
                return "help/op-tx.md";
            } else if (selectedElement.getHumanType().contains("Battery")) {
                return "help/battinfo.md";
            }
            return "help/general.md";
        }
    }
    ```

### 12.4. Detailed User Interface Specifications
The plugin sidebar panel must be implemented using a JavaFX `JFXPanel` wrapper, adhering to these structural constraints:
*   **Layout Specifications:**
    *   *Top Section:* Selected element name and type metadata card (12px padding, rounded corners, subtle border shadow).
    *   *Middle Section:* Tabs to switch between **SBVR English View** (using fixed Courier New font and formal color tokens) and **Search & Align Panel** (with a text search input field and a scrollable autocomplete results menu).
    *   *Bottom Section:* Audit validation dashboard displaying the status badge (Green for Consistent, Red for Violations) and a terminal log console.
*   **GUI Interaction States:**
    *   *Busy State:* Disable the "Run Audit" button and show a spinning loading indicator (`ProgressIndicator`) when the HermiT reasoner is executing.
    *   *Offline State:* If the BioPortal network endpoint drops, display a warning banner at the top of the sidebar: `"Offline Mode Active - Using Cached Local Ontologies"`.
*   **Accessibility Standards (WCAG Compliance):**
    *   All sidebar text color contrasts must achieve a minimum contrast ratio of **4.5:1** against the background.
    *   The search and mapping elements must support full keyboard navigation (Tab focusing and Enter mapping confirmation).





