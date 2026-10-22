import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from PIL import Image

file=Path(__file__).with_name("build_dbpulse_icon.py")
spec=importlib.util.spec_from_file_location("dbpulse_icon_builder",file)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class DBPulseIconTests(unittest.TestCase):
    def test_icon_contains_small_desktop_and_high_resolution_sizes(self):
        with TemporaryDirectory() as directory:
            output=Path(directory)/"DBPulse.ico"
            mod.build_icon(output=output)
            self.assertTrue(output.is_file())
            with Image.open(output) as ico:
                self.assertTrue(set(mod.SIZES).issubset(ico.ico.sizes()))
            self.assertGreater(output.stat().st_size,1000)

    def test_never_overwrite_existing_icon(self):
        with TemporaryDirectory() as directory:
            output=Path(directory)/"DBPulse.ico"
            output.write_bytes(b"KEEP")
            with self.assertRaisesRegex(RuntimeError,"ALREADY_EXISTS"):
                mod.build_icon(output=output)
            self.assertEqual(output.read_bytes(),b"KEEP")

    def test_missing_asset_fails_closed(self):
        with TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaisesRegex(RuntimeError,"SOURCE_MISSING"):
                mod.build_icon(source=root/"missing.webp",output=root/"icon.ico")


if __name__ == "__main__":
    unittest.main()
