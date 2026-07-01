# UAF to and OWL Ontology

This subsection is used to analyse UAF and create an equivelent ontology. 

## Some rules for the AI/LLM

- Must have assets that are available and referencable (no halucination). If you can't access a resource of website, tell the user and we will get it for you. 
- Best practice is important. For example of creating ontologies, working with reaonser, UAF modeling, coding, etc. Any task should verify we have good best practices to guide the work. 
- Ask specific questions when in doubt or there are choices that are best asked of the stakholder.
- Interview me until we have uncovered enough information to continue.  

## Much work to do

This is going to be a lot of work, but with luck, the LLM tools will help us. Here are a few initial steps:

### Reading UML to mine the standard

This is job #1 that needs to be done. There are 2 primary model to read, the Domain Meta Model(DMM) and the UAFML profile. There is an additional mapping for UAF 2.0 and how it is mapped to SysMLv2. This can also mean a true mapping of KerML and SysMLv2 models. 

### Choosing of a foundation ontology

Here we need to best understand foundation ontologies to base our ontology on. This can be UFO, GUFO, and many others. We need to be able to represent a number of concepts from UML, UAF, SysML 1 and 2,and others as needed. We might also leverage any OWL work in DoDAF, IDEAS, or MODEM. 

### Goals - Ensuring Utility

THe goal of the project is to allow  the following use cases:

#### Ensuting modeled element is correct according to the ontology.

If you are modeling a concept, have you correctly used the concept in UAF correctly? For example, if the concept is a service, is it really a service? Or, a capability, are we really modeling a capability or confusing this with a requirement? 

One big mistake is modeling a real organization in the operational view, versus in the Perssonel view. Or modeling the type as the actual. 

### You are here, what is next?

One of the faults of UAF is its scale. It is not always easy to know what is next or choose a path. This is true too of other frameworks and even the base of UML/SysMLv2 that we also have access to. How do we navigate the creation of models without overly specifying the content while reaching the goals we have for the model. This means always having the goals in mind to aim at, which working down the risks. Some of this is best practice, some is process, but it is critically the model and the network of relationships and measures of partial completeness to correct specification that interrelates with what comes before and after on the way to goals. We are hoping that the ontology guides this work, but we also know there is more that needs to exist that can guide the user, but also not get in the way if the user. We also want to be able to ask questions with a reasoner that aid in detecting gaps, inconsistencies by mirring the model with an instance of the model in OWL. This goal is the hardest to define as it will need to grow to increase the utility.

### Conversion of Other Materials to UAF assets

If we have a document that is either a asset describing a part of an architecture or instructions to follow, the ontology should aid in classifying the content for use in UAF models. This can include RFP, design documents, requirements, policy, doctrine, historical documents, text books, training material, web sites, etc. The goal is to have creation, validation and traceability to such sources. This is both accelleration, correctness, and sources of truth for the model to ensure the models ar good and fit for their purpose. 

### Mapping to Detail design

By having the UAF model and ontology can we better create the assets for the actual transformations of the enterprise? How do we link UAF to the designs and real-world data to ensure the models are being followed or feed back to a UAF model so we can show success, gaps, or variation of the plan to reality?


### Overcommign the Tyrany of Diagram Pallets

One issue with UAF is that there is little to guide creation. By changing input to interoggative creation, we change the process from thinking about what to model, to being asked questions that elicite the answers to create the model. We are still modeling and creating drawings, but at any point we are filling gaps, confirming information to ensure correctness or detect gaps. We also want to guide this to avoid over specificity and avoid unnecissary details by always keeping the goals in mind and prioritizing the reduction of risks. We also want to take advantage of what we know, versus questions we have now. Thus on demand, we can guide the creation of a model. 

### Ensuring Model Architecture correctness

A high cost of UAF is ensuring models are readable by the creators and the stakeholderss. Are we properly managing and maintaining views and viewpoints and are they consumable by the stakeholders? This means an understanding of a reasonable view to meet a viewppoint. There are the generic views and viewpoints of UAF and then there are the specificity of particular stakeholders with specific viewpoints throughout the varius parts of the UAF model. This is partially the live ediiting of views and viewpoints as the model is created, but as importantly the creation of the required viewpoints and synthesis of the view goals.

Note: This needs to be rewriten and reformatted to better model the goals, create use cases, and create a plan. 

### The Chain of Knowledge
UAF is very large and understanding how an element in the strategy that is complex in the strategy,, but then is traced to operations, and onward through each row and coumn of the grid. We should be able to select any element and see the relevent data from strategy to acutal resources and  the real world assets in other tools. This aids in discovery and gap assesment. It also makes the model smaller to the reviewer by focusing to the threads of information that relate to key goals and facts of the 1model. This also means we need to be able to view such data and navigate it either in the standard views, synthesised views, reports, presentations, and even video tours of the model. This aids to in review and your ability to see what is in the model as you are developing or editing the model. It should also show impacts as they occur, even if the impact is predected, versus actual. For example, if we add a risk, what is a map of my to do tasks and elements we need to create?

### Easy to maintain ontologies and tooling

UAF is evolving as will our use of UAF and other technologies like the related ontology or theontologies created as the UAF model evolves. This means everything needs to be properly designed and tools to manage the system are as important as the tools themselves. 

### Education

We need to constantly improve the documentation and the trainability of the system. Agility as the system grows also means that users need to be able to catch up to new capabilities. As this becomes more complex, it also means always presenting contextual help as the tool is used. This is esepecially important in UAF because EA tasks are often performed on a rare basis rather than one repeated task. EA models require thinking very differently about very different concepts. Even if we have done one part successfully, the next task requires different elements from a different point of view, with different goals.


## assets

### Core Models
These models are created mostly in XMI, so need to be able to read and undderstand directly or via the CATIA Magic API. There is also the SysMLv2 text files as well as access via the CATIA Magic API. Let's target the injestion of the DMM and the profile source model first as we have that in one file. Secondarily SysMLv2 for UAF2 and follow with MODEM and IDEAS to validate some of the ontology assumtions they have.

- UAF Domain Meta Model (DMM)
- UAF profile
- UAF2 for SysML
- MODEM
- IDEAS
- Example models

### Instructions and doctrine, documents, papers

- RDF Database
- Reasoners and Query engins
- Other ontologies
- Other standards and capabilities for ontology tools
- SBVR - Ontology to English (need to translate ontology ideas to muggle speak
- API4KP - Meta-API for Heterogeneous Knowledge Platforms
- Graph visualization
- 
- OntoUML
- CCM/VOM/Protoge or other Ontology visualizers
