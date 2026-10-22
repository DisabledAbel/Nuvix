"""Permanent package, real APK metadata, history and workflow mutation regressions."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
from subprocess import CompletedProcess
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_identity", ROOT / "scripts/release-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)
control = identity.control


class IdentityContractsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "scripts/create-release.py",
                     "scripts/release-control.py", "scripts/release-identity.py"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, path)
        shutil.copytree(ROOT / ".github/workflows", self.root / ".github/workflows")

    def test_production_identity_is_distinct_from_namespace(self):
        identity.check_source(self.root)
        gradle = (self.root / "app/build.gradle.kts").read_text()
        self.assertIn('namespace = "com.disabledabel.nuvix"', gradle)
        self.assertIn('applicationId = "com.nuvix.player"', gradle)

    def test_wrong_production_id_and_debug_signing_are_rejected(self):
        path = self.root / "app/build.gradle.kts"
        original = path.read_text()
        for changed in [original.replace('applicationId = "com.nuvix.player"', 'applicationId = "changed.app"'),
                        original.replace('signingConfigs.getByName("upload") else null', 'signingConfigs.getByName("debug") else null')]:
            path.write_text(changed)
            with self.assertRaises(ValueError):
                identity.check_source(self.root)

    def test_hardcoded_authority_and_startup_class_regressions(self):
        path = self.root / "app/src/main/AndroidManifest.xml"
        original = path.read_text()
        for changed in [original.replace('${applicationId}.fileprovider', 'com.disabledabel.nuvix.fileprovider'),
                        original.replace('app.opentv.OpenTvApp', 'com.nuvix.player.OpenTvApp')]:
            path.write_text(changed)
            with self.assertRaises(ValueError):
                identity.check_source(self.root)

    def test_required_verifier_cannot_disappear(self):
        (self.root / "scripts/create-release.py").unlink()
        with self.assertRaises(ValueError):
            identity.check_source(self.root)

    def test_current_workflows_preserve_secret_free_validation(self):
        identity.check_workflows(self.root)

    def test_alternate_workflow_is_rejected_including_yaml_extension(self):
        (self.root / ".github/workflows/bypass.yaml").write_text('on: push\njobs: {}\n')
        with self.assertRaises(ValueError):
            identity.check_workflows(self.root)

    def mutate(self, filename, change):
        path = self.root / ".github/workflows" / filename
        original = path.read_text()
        workflow = yaml.load(original, Loader=yaml.BaseLoader)
        change(workflow)
        path.write_text(yaml.safe_dump(workflow))
        try:
            with self.assertRaises(ValueError):
                identity.check_workflows(self.root)
        finally:
            path.write_text(original)

    def test_approval_verifier_and_independent_fingerprint_cannot_be_removed(self):
        for change in [lambda w: w['on'].update(push={}),
                       lambda w: w['jobs']['publish'].pop('environment'),
                       lambda w: w['jobs']['publish'].update(needs=['validate']),
                       lambda w: w['jobs']['publish']['env'].update(EXPECTED_SIGNING_CERT_SHA256='a' * 64),
                       lambda w: w['jobs']['publish']['steps'].pop(2),
                       lambda w: w['jobs']['publish']['steps'][2].update({'if': 'false'}),
                       lambda w: w['jobs']['publish'].update({'continue-on-error': 'true'}),
                       lambda w: w['jobs']['publish'].update({'if': w['jobs']['publish']['if'] + ' || true'})]:
            self.mutate('release.yml', change)

    def test_pr_secrets_write_permissions_and_nightly_trigger_rejected(self):
        for change in [lambda w: w['jobs']['build'].update(env={'KEYSTORE_PASSWORD': '${{ secrets.KEYSTORE_PASSWORD }}'}),
                       lambda w: w['jobs']['build'].update(permissions={'contents': 'write'}),
                       lambda w: w['on'].update(schedule=[{'cron': '0 0 * * *'}]),
                       lambda w: w['jobs']['build']['steps'].append({'run': 'gh release create v1.0.0'}),
                       lambda w: w['jobs']['build'].update({'if': 'false'}),
                       lambda w: w['jobs']['build']['steps'][3].update({'continue-on-error': 'true'})]:
            self.mutate('build.yml', change)

    def test_unsigned_apk_inspection_checks_binary_and_committed_versions(self):
        path = self.root / "output-metadata.json"
        metadata = {'applicationId': identity.PRODUCTION_ID,
                    'elements': [{'versionName': '0.1.0', 'versionCode': 21, 'outputFile': 'app-release-unsigned.apk'}]}
        path.write_text(json.dumps(metadata))
        (self.root / 'app-release-unsigned.apk').write_bytes(b'fixture')
        badging = "package: name='com.nuvix.player' versionCode='21' versionName='0.1.0'\n"
        unsigned = CompletedProcess([], 1, b'', b'DOES NOT VERIFY\nERROR: Missing signature')
        with patch.object(control.release, 'run', return_value=badging), \
             patch.object(identity.subprocess, 'run', return_value=unsigned):
            self.assertEqual(('0.1.0', 21), identity.check_unsigned(path, Path('sdk'), self.root))
            with patch.object(control.release, 'run', return_value=badging.replace('com.nuvix.player', 'wrong.app')):
                with self.assertRaises(ValueError):
                    identity.check_unsigned(path, Path('sdk'), self.root)
            with patch.object(identity.subprocess, 'run', return_value=CompletedProcess([], 0, b'Signed', b'')):
                with self.assertRaisesRegex(ValueError, 'unsigned'):
                    identity.check_unsigned(path, Path('sdk'), self.root)
            metadata['elements'][0]['versionCode'] = 20
            path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, 'version'):
                identity.check_unsigned(path, Path('sdk'), self.root)


class PackageHistoryTest(unittest.TestCase):
    def previous(self, tag='v1.0.0-rc.1', code=22, package=identity.PRODUCTION_ID, **extra):
        return ({'tag_name': tag}, {'applicationId': package, 'versionName': tag[1:], 'versionCode': code,
                                  'signingCertificateSha256': 'a' * 64, **extra})

    def test_old_package_does_not_constrain_new_code_sequence(self):
        control.check_history('v0.1.0', 21, [self.previous('v0.0.9', 999, control.release.LEGACY_APPLICATION_ID)])

    def test_unknown_package_and_missing_metadata_fail_closed(self):
        for previous in [self.previous(package='unknown.app'), ({'tag_name': 'v0.0.9'}, None)]:
            with self.assertRaises(ValueError):
                control.check_history('v1.0.0', 23, [previous])

    def test_rc_to_stable_requires_a_higher_code(self):
        for code in (21, 22):
            with self.assertRaisesRegex(ValueError, 'versionCode'):
                control.check_history('v1.0.0', code, [self.previous()])
        control.check_history('v1.0.0', 23, [self.previous()])

    def test_rc_after_stable_or_lower_rc_rejected_even_with_higher_code(self):
        for tag, previous in [('v1.0.0-rc.2', self.previous('v1.0.0')),
                              ('v1.0.0-rc.1', self.previous('v1.0.0-rc.2')),
                              ('v0.9.0', self.previous('v1.0.0'))]:
            with self.assertRaises(ValueError):
                control.check_history(tag, 23, [previous])

    def test_tag_metadata_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError, 'versionName'):
            control.check_history('v1.0.0', 23, [self.previous(versionName='1.0.0')])

    def test_approved_fingerprint_must_match_published_production_history(self):
        control.check_history('v1.0.0', 23, [self.previous()], 'a' * 64)
        with self.assertRaisesRegex(ValueError, 'certificate'):
            control.check_history('v1.0.0', 23, [self.previous()], 'b' * 64)


if __name__ == '__main__':
    unittest.main()
