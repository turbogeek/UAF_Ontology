import unittest
import re

def sanitize_id(name):
    cleaned = re.sub(r'[^a-zA-Z0-9\s_-]', '', name)
    return re.sub(r'[-\s]+', '-', cleaned).strip('-').lower()

class TestSysMLParsers(unittest.TestCase):
    
    def test_sanitize_id(self):
        self.assertEqual(sanitize_id("Search"), "search")
        self.assertEqual(sanitize_id("Tactical C2"), "tactical-c2")
        self.assertEqual(sanitize_id("Summary & Overview"), "summary-overview")
        
    def test_stereotype_pattern(self):
        pattern = re.compile(
            r"#([\w:]+)\s+(part\s+def|action\s+def|part|action)\s*(?:def\s+)?'([^']+)'|"
            r"#([\w:]+)\s+(part\s+def|action\s+def|part|action)\s*(?:def\s+)?(\w+)",
            re.IGNORECASE
        )
        
        # Test quoted definition
        m1 = pattern.search("#operationalPerformer part def 'Tactical C2';")
        self.assertIsNotNone(m1)
        self.assertEqual(m1.group(1), "operationalPerformer")
        self.assertEqual(m1.group(2), "part def")
        self.assertEqual(m1.group(3), "Tactical C2")
        
        # Test unquoted definition
        m2 = pattern.search("#operationalPerformer part def Search;")
        self.assertIsNotNone(m2)
        self.assertEqual(m2.group(4), "operationalPerformer")
        self.assertEqual(m2.group(5), "part def")
        self.assertEqual(m2.group(6), "Search")
        
        # Test stereotyped usage
        m3 = pattern.search("#operationalConfiguration part 'SAR Operation' {")
        self.assertIsNotNone(m3)
        self.assertEqual(m3.group(1), "operationalConfiguration")
        self.assertEqual(m3.group(2), "part")
        self.assertEqual(m3.group(3), "SAR Operation")
        
    def test_nested_parts_and_connections(self):
        body = """
            #operationalPerformer part sn : 'Operational Taxonomy'::Search {
                in item ds : 'Operational Information'::'Distress Signal';
            }
            #operationalPerformer part rn : 'Operational Taxonomy'::Rescue;
            #operationalConnector connection oc3 connect dp to sn;
        """
        
        # Parse parts using the updated robust pattern
        parts = {}
        for m in re.finditer(r"part\s+(\w+)\s*:\s*([^;{\[]+)", body):
            var_name, type_raw = m.group(1), m.group(2)
            type_clean = type_raw.strip().strip("'").split("::")[-1].strip()
            parts[var_name] = type_clean
            
        self.assertEqual(parts.get("sn"), "Search")
        self.assertEqual(parts.get("rn"), "Rescue")
        
        # Parse connections
        connections = []
        for m in re.finditer(r"connection\s+(\w+)\s+connect\s+(\w+)\s+to\s+(\w+)", body):
            int_name, src_var, tgt_var = m.group(1), m.group(2), m.group(3)
            connections.append((int_name, src_var, tgt_var))
            
        self.assertEqual(len(connections), 1)
        self.assertEqual(connections[0], ("oc3", "dp", "sn"))

if __name__ == '__main__':
    unittest.main()
