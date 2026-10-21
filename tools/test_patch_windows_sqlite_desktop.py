"""Cross-platform, nonmutating checks for the surgical SQLite desktop patch."""
import unittest
import patch_windows_sqlite_desktop as patch


class PatchSmokeTests(unittest.TestCase):
    def setUp(self):
        self.source = '''package com.dbpulse.desktop;
public class WindowsDesktopBoot {
    public static void configure(SpringApplication springApp) {
        String metadataPassword=loadMetadataPassword();
        Map<String,Object> props=new HashMap<>();
        props.put("server.address","127.0.0.1");
        props.put("server.port",18081);
        props.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5544/dbpulse");
        props.put("spring.datasource.username","dbpulse");
        props.put("spring.datasource.password",metadataPassword);
        props.put("dbpulse.credentials.windows-dpapi-enabled",true);
        springApp.addInitializers(context -> props.toString());
        springApp.setHeadless(false);
    }
    /** Modified comment should not matter. */
    public static void openDashboard() {}
}
'''

    def test_lf_supported(self):
        result = patch.replace_method(self.source)
        self.assertIn('DesktopSqliteStorage.isOptedIn', result)
        self.assertIn('springApp.setHeadless(false);', result)
        self.assertIn('props.put("dbpulse.credentials.windows-dpapi-enabled",true);', result)
        self.assertIn('String metadataPassword=loadMetadataPassword();', result)
        self.assertIn('Path dbpulseDirectory', result)

    def test_windows_crlf_preserved(self):
        source = self.source.replace('\n', '\r\n')
        result = patch.replace_method(source)
        self.assertIn('\r\n', result)
        self.assertNotIn('\r\r\n', result)
        self.assertEqual(result.replace('\r\n', '\n'), patch.replace_method(self.source))

    def test_crlf_with_bom_handled_by_main_layer(self):
        raw = b'\xef\xbb\xbf' + self.source.replace('\n', '\r\n').encode('utf-8')
        result = patch.replace_method(raw[3:].decode('utf-8'))
        self.assertIn('\r\n', result)

    def test_second_run_refused(self):
        with self.assertRaisesRegex(ValueError, 'PATCH_ALREADY_APPLIED'):
            patch.replace_method(patch.replace_method(self.source))

    def test_unexpected_old_pg_code_refused(self):
        changed = self.source.replace('127.0.0.1:5544/dbpulse', 'different-host:5544/dbpulse')
        with self.assertRaisesRegex(ValueError, 'WINDOWS_BOOT_SOURCE_UNEXPECTED'):
            patch.replace_method(changed)

    def test_missing_method_refused(self):
        with self.assertRaisesRegex(ValueError, 'WINDOWS_BOOT_METHOD_SIGNATURE_MISMATCH'):
            patch.replace_method(self.source.replace('void configure(', 'void changed('))

    def test_other_code_and_custom_comments_preserved(self):
        source = self.source.replace('springApp.setHeadless(false);',
                                     '// other { braces }\n        springApp.setHeadless(false);')
        self.assertIn('// other { braces }', patch.replace_method(source))

    def test_no_changes_outside_configure_method(self):
        source = self.source.replace('public static void openDashboard() {}',
                                     'public static void openDashboard() { throw new RuntimeException("do not edit"); }')
        result = patch.replace_method(source)
        self.assertTrue(result.endswith('public static void openDashboard() { throw new RuntimeException("do not edit"); }\n}\n'))

    def test_whitespace_tolerant_signature(self):
        source = self.source.replace('public static void configure(SpringApplication springApp)',
                                     'public  static void configure ( SpringApplication springApp )')
        result = patch.replace_method(source)
        self.assertIn('DesktopSqliteStorage.configure', result)


if __name__ == '__main__':
    unittest.main()
