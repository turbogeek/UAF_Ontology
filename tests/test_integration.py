import unittest
import rdflib
from owlready2 import *
from pathlib import Path

class TestEcosystemIntegration(unittest.TestCase):
    
    def test_reasoning_consistency(self):
        g = rdflib.Graph()
        ontologies_dir = Path("e:/_Documents/git/UAF_Ontology/UAF to OWL Goals/ontologies")
        
        # Load core mapping files
        g.parse(str(ontologies_dir / "kerml.ttl"), format="turtle")
        g.parse(str(ontologies_dir / "sysml2.ttl"), format="turtle")
        g.parse(str(ontologies_dir / "uafsml_ontology.ttl"), format="turtle")
        g.parse(str(ontologies_dir / "uaf_ontology.ttl"), format="turtle")
        g.parse(str(ontologies_dir / "uaf_bridge.ttl"), format="turtle")
        g.parse(str(ontologies_dir / "uafv2_instances.ttl"), format="turtle")
        
        # Strip imports so owlready2 doesn't try to query online
        for s, p, o in list(g.triples((None, rdflib.OWL.imports, None))):
            g.remove((s, p, o))
            
        temp_xml = Path("e:/_Documents/git/UAF_Ontology/tests/temp_test.xml")
        g.serialize(destination=str(temp_xml), format="xml")
        
        try:
            onto = get_ontology(f"file://{temp_xml.as_posix()}").load()
            with onto:
                sync_reasoner()
            
            # If we reach here, reasoning succeeded without inconsistencies
            reasoning_passed = True
        except Exception as e:
            reasoning_passed = False
            print(f"Reasoner error details: {e}")
        finally:
            if temp_xml.exists():
                temp_xml.unlink()
                
        self.assertTrue(reasoning_passed, "integrated ontologies must be logically consistent under HermiT")

if __name__ == '__main__':
    unittest.main()
