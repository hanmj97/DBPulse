import unittest
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from apply_sqlite_lab_origin import updated,OLD,NEW

class OriginPatchTests(unittest.TestCase):
    def test_loopback_only_port(self):
        s='class X { '+OLD+' }'
        self.assertIn(NEW,updated(s))
        self.assertIn('http://127.0.0.1:18082',updated(s))
    def test_already_patched_rejects(self):
        with self.assertRaisesRegex(ValueError,'ALREADY_PATCHED'):
            updated(NEW)
    def test_unrecognized_source_rejects(self):
        with self.assertRaisesRegex(ValueError,'SOURCE_MISMATCH'):
            updated('some source change')
if __name__=='__main__':unittest.main()
