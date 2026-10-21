"""Self-contained structural regression tests for the local source patcher."""
import sys
from pathlib import Path
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apply_sqlite_timestamp_read_fix import (BASE, FILES, HELPER, TEST,
                                              PatchError, plan, transform)

class TimestampSourcePatchTest(unittest.TestCase):
    def test_all_files_modified_with_expected_number_of_timestamp_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for rel, n in FILES.items():
                src = '\n'.join('rs.getTimestamp("collected_at").toInstant()' for _ in range(n))
                if rel.endswith("LocalTargetService.java"):
                    src = "import com.dbpulse.config.MssqlTargetProperties;\n" + src
                if rel.endswith("IncidentRepository.java"):
                    src += ("\nTimestamp resolved = rs.getTimestamp(\"resolved_at\");"
                            "\nresolved == null ? null : resolved.toInstant()") * 2
                path = root / BASE / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(src)
            results = plan(root)
            self.assertEqual(9, len(results))
            self.assertIn(root / HELPER, results)
            self.assertIn(root / TEST, results)
            for rel in FILES:
                self.assertNotIn('getTimestamp("collected_at").toInstant()', results[root / BASE / rel])
            incident=results[root / BASE / 'persistence/IncidentRepository.java']
            self.assertEqual(2,incident.count('ImportedTimestampReader.instantOrNull(rs, "resolved_at")'))
            self.assertEqual(1,results[root / BASE / 'service/LocalTargetService.java'].count('import com.dbpulse.persistence.ImportedTimestampReader;'))

    def test_does_not_silently_patch_unknown_repository_source(self):
        with self.assertRaises(PatchError):
            transform('persistence/MetricRepository.java', 'not a timestamp', 1)

    def test_rejects_second_application(self):
        with self.assertRaises(PatchError):
            transform('persistence/MetricRepository.java',
                      'ImportedTimestampReader.instant(rs, "collected_at")', 1)

if __name__ == '__main__':
    unittest.main()
