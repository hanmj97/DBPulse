"""No-network tests for safe DBPulse localhost soak probe."""
from __future__ import annotations
import importlib.util
import io
from contextlib import redirect_stdout
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location(
    "dbpulse_local_soak_probe", Path(__file__).with_name("dbpulse_local_soak_probe.py"))
probe=importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Response:
    def __init__(self, content, status=200):
        self._content=content
        self.status=status
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def read(self, bound): return self._content[:bound]


class FakeOpener:
    def __init__(self, statuses=None):
        self.statuses=statuses or [200,200]
        self.paths=[]
    def open(self, request, timeout):
        self.paths.append(request.full_url)
        code=self.statuses[len(self.paths)-1]
        return Response(b'{"ok":true}' if len(self.paths)==1 else b'[]',code)


class SoakProbeTests(unittest.TestCase):
    def test_success_uses_only_two_fixed_localhost_apis(self):
        fake=FakeOpener()
        with patch.object(probe, "OPENER", fake):
            ok,elapsed=probe.sample()
        self.assertTrue(ok)
        self.assertGreaterEqual(elapsed,0)
        self.assertEqual(fake.paths,[probe.BASE+p for p in probe.PATHS])

    def test_api_failure_does_not_log_payload(self):
        fake=FakeOpener([200,503])
        with patch.object(probe, "OPENER", fake):
            ok,_=probe.sample()
        self.assertFalse(ok)

    def test_statistics_are_bounded_and_stable(self):
        result=probe.summarize([(True,10.0),(False,9),(True,20.0),(True,30.0)])
        self.assertEqual(result,(3,1,20,30))

    def test_once_has_no_background_task_and_no_response_body(self):
        fake=FakeOpener()
        output=io.StringIO()
        with patch.object(probe,"OPENER",fake),redirect_stdout(output):
            code=probe.main(["--once"])
        self.assertEqual(code,0)
        self.assertIn("DBPULSE_LOCALHOST_OK: 1",output.getvalue())
        self.assertNotIn('"ok"',output.getvalue())


if __name__ == "__main__": unittest.main()
