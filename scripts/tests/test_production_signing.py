"""One-password signing storage and disposable-key encrypted recovery; never use real API writes."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('simple_signing', ROOT / 'scripts/production-signing.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
spec = importlib.util.spec_from_file_location('setup_identity', ROOT / 'scripts/release-identity.py')
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


class SetupPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.load((ROOT / '.github/workflows/setup-production-signing.yml').read_text(), Loader=yaml.BaseLoader)

    def reject(self, mutate):
        workflow = copy.deepcopy(self.workflow)
        mutate(workflow)
        with self.assertRaises(ValueError):
            identity.check_signing_setup(workflow)

    def test_only_one_user_secret_and_builtin_token_are_needed(self):
        identity.check_signing_setup(self.workflow)
        identity.check_workflows()
        self.assertNotIn('SIGNING_SETUP_TOKEN', json.dumps(self.workflow))
        self.assertNotIn('BACKUP_ARTIFACT_ID', json.dumps(self.workflow))

    def test_setup_is_manual_main_only_and_serialized_with_releases(self):
        for event in ('push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_call'):
            self.reject(lambda w: w['on'].update({event: {}}))
        self.reject(lambda w: w['jobs']['setup'].update({'if': 'true'}))
        self.reject(lambda w: w['concurrency'].update(group='separate-from-releases'))
        self.reject(lambda w: w['concurrency'].update({'cancel-in-progress': 'true'}))
        self.reject(lambda w: w['permissions'].update(contents='read'))
        self.reject(lambda w: w['env'].update(TRUSTED_RELEASE_ACTORS='anyone'))

    def test_no_plaintext_upload_or_password_in_dependencies(self):
        self.reject(lambda w: w['jobs']['setup']['steps'][5].update(run='gh release upload key plaintext.jks'))
        self.reject(lambda w: w['jobs']['setup']['steps'][3].update(env={'PASSWORD': '${{ secrets.SIGNING_BACKUP_PASSWORD }}'}))
        self.reject(lambda w: w['jobs']['setup'].update(env={'PASSWORD': '${{ secrets.SIGNING_BACKUP_PASSWORD }}'}))
        self.reject(lambda w: w['jobs']['setup']['steps'][5]['env'].update(GH_TOKEN='${{ secrets.SIGNING_SETUP_TOKEN }}'))

    def test_generation_storage_and_cleanup_cannot_be_skipped_or_reordered(self):
        for index in (1, 4, 5):
            self.reject(lambda w: w['jobs']['setup']['steps'][index].update({'if': 'false'}))
            self.reject(lambda w: w['jobs']['setup']['steps'][index].update({'continue-on-error': 'true'}))
        def reorder(w):
            steps = w['jobs']['setup']['steps']
            steps[4], steps[5] = steps[5], steps[4]
        self.reject(reorder)
        self.reject(lambda w: w['jobs']['setup']['steps'][1]['env'].pop('EXISTING_SIGNING_IDENTITY'))
        self.reject(lambda w: w['jobs']['setup']['steps'][6].pop('if'))


class SetupPreflightTest(unittest.TestCase):
    def setUp(self):
        self.env = {'GITHUB_REPOSITORY': signing.control.REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40,
                    'GITHUB_RUN_ID': '10', 'GITHUB_ACTOR': 'DisabledAbel', 'GITHUB_TRIGGERING_ACTOR': 'DisabledAbel',
                    'GH_TOKEN': 'disposable-workflow-token', 'SIGNING_BACKUP_PASSWORD': 'disposable-backup-password-for-tests',
                    'EXISTING_SIGNING_IDENTITY': 'false'}
        base = signing.control.BASE
        self.responses = {
            f'{base}/actions/runs/10': {'event': 'workflow_dispatch', 'head_branch': 'main', 'head_sha': 'a' * 40,
                'status': 'in_progress', 'conclusion': None, 'path': '.github/workflows/setup-production-signing.yml'},
            f'{base}/git/ref/heads/main': {'object': {'sha': 'a' * 40}},
            f'{base}/collaborators/DisabledAbel/permission': {'permission': 'admin'},
            f'{base}/releases?per_page=100&page=1': [],
        }

    def preflight(self):
        with patch.dict(os.environ, self.env, clear=True), \
             patch.object(signing.control, 'api', side_effect=lambda path: self.responses[path]) as api, \
             patch.object(signing.control.release, 'run', return_value='a' * 40):
            signing.preflight()
        self.assertFalse(any('/actions/secrets' in c.args[0] or '/actions/variables' in c.args[0] for c in api.call_args_list))

    def test_preflight_does_not_need_secret_or_variable_management_apis(self):
        self.preflight()

    def test_untrusted_context_stops_before_api(self):
        for key, value in [('GH_TOKEN', ''), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_RUN_ATTEMPT', '2'),
                           ('GITHUB_REPOSITORY', 'other/Nuvix')]:
            with self.subTest(key=key), patch.dict(os.environ, dict(self.env, **{key: value}), clear=True), \
                 patch.object(signing.control, 'api') as api, self.assertRaises(ValueError):
                signing.preflight()
            api.assert_not_called()

    def test_stale_main_and_wrong_workflow_are_rejected(self):
        self.responses[f'{signing.control.BASE}/git/ref/heads/main'] = {'object': {'sha': 'b' * 40}}
        with self.assertRaisesRegex(ValueError, 'main changed'):
            self.preflight()
        self.responses[f'{signing.control.BASE}/git/ref/heads/main'] = {'object': {'sha': 'a' * 40}}
        self.responses[f'{signing.control.BASE}/actions/runs/10']['path'] = '.github/workflows/release.yml'
        with self.assertRaisesRegex(ValueError, 'Unexpected signing'):
            self.preflight()

    def test_password_existing_credentials_and_release_guards(self):
        self.env['SIGNING_BACKUP_PASSWORD'] = 'too-short'
        with self.assertRaisesRegex(ValueError, 'at least 24'):
            self.preflight()
        self.env['SIGNING_BACKUP_PASSWORD'] = 'disposable-backup-password-for-tests'
        for flag in ('true', ''):
            self.env['EXISTING_SIGNING_IDENTITY'] = flag
            with self.assertRaisesRegex(ValueError, 'already exist'):
                self.preflight()
        self.env['EXISTING_SIGNING_IDENTITY'] = 'false'
        for release in ({'tag_name': 'v1.0.0', 'draft': False}, {'tag_name': signing.STORE_TAG, 'draft': True}):
            self.responses[f'{signing.control.BASE}/releases?per_page=100&page=1'] = [release]
            with self.assertRaisesRegex(ValueError, 'already exists'):
                self.preflight()

    def test_api_failure_reports_only_status(self):
        with patch.object(signing.control, 'api', side_effect=RuntimeError('PRIVATE-CANARY (HTTP 403)')):
            with self.assertRaises(ValueError) as error:
                signing.api(f'{signing.control.BASE}/releases')
        self.assertIn('HTTP 403', str(error.exception))
        self.assertNotIn('PRIVATE-CANARY', str(error.exception))

    def test_store_requires_one_draft_and_preserves_existing_identity(self):
        saved = {'id': 7, 'tag_name': signing.STORE_TAG, 'draft': True}
        with patch.object(signing.control, 'pages', return_value=[saved]):
            self.assertEqual(7, signing.store_release()['id'])
        for releases in ([], [dict(saved, draft=False)], [saved, saved]):
            with patch.object(signing.control, 'pages', return_value=releases), self.assertRaises(ValueError):
                signing.store_release()


class BackupAndStorageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.fixture.cleanup)
        cls.env = {'RUNNER_TEMP': cls.fixture.name, 'GITHUB_RUN_ID': '10', 'GITHUB_SHA': 'a' * 40,
                   'SIGNING_BACKUP_PASSWORD': 'disposable-backup-password-for-tests'}
        with patch.dict(os.environ, cls.env), patch.object(signing, 'preflight'), \
             patch.object(signing.control, 'api', return_value={'id': 7}):
            signing.generate()  # Disposable test identity, deleted with the fixture.
        cls.fixture_root = Path(cls.fixture.name) / 'nuvix-first-signing'
        cls.record = signing.verify_backup(cls.fixture_root / 'backup/nuvix-signing-backup.zip', cls.env['SIGNING_BACKUP_PASSWORD'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'nuvix-first-signing'
        shutil.copytree(self.fixture_root, self.root)
        self.env = dict(type(self).env, RUNNER_TEMP=self.temp.name,
                        GITHUB_STEP_SUMMARY=str(Path(self.temp.name) / 'summary'),
                        GITHUB_ENV=str(Path(self.temp.name) / 'env'), LEGACY_SIGNING_CONFIGURED='false',
                        EXPECTED_SIGNING_CERT_SHA256='')
        self.data = (self.root / 'backup/nuvix-signing-backup.zip').read_bytes()
        self.asset = {'id': 9, 'name': signing.STORE_ASSET, 'state': 'uploaded', 'size': len(self.data),
                      'digest': 'sha256:' + hashlib.sha256(self.data).hexdigest()}
        self.saved = {'id': 7, 'draft': True, 'tag_name': signing.STORE_TAG, 'assets': [self.asset],
                      'html_url': 'https://github.com/DisabledAbel/Nuvix/releases/tag/nuvix-signing-key'}

    def test_backup_restores_same_key_and_rejects_incorrect_password(self):
        record = signing.verify_backup(self.root / 'backup/nuvix-signing-backup.zip', self.env['SIGNING_BACKUP_PASSWORD'])
        self.assertEqual(self.record['fingerprint'], record['fingerprint'])
        with self.assertRaises(Exception):
            signing.verify_backup(self.root / 'backup/nuvix-signing-backup.zip', 'incorrect-password')

    def test_generation_reserves_draft_before_keytool(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, dict(self.env, RUNNER_TEMP=directory)), \
             patch.object(signing, 'preflight'), patch.object(signing.control, 'api', return_value={'id': 7}) as api:
            def fail(args, **kwargs):
                self.assertEqual('POST', api.call_args.kwargs['method'])
                self.assertTrue(api.call_args.kwargs['data']['draft'])
                self.assertEqual(signing.STORE_TAG, api.call_args.kwargs['data']['tag_name'])
                raise ValueError('keytool unavailable')
            with patch.object(signing, 'tool', side_effect=fail), self.assertRaises(ValueError):
                signing.generate()

    def test_save_uploads_only_encrypted_zip_without_secret_management(self):
        with patch.dict(os.environ, self.env), patch.object(signing, 'validate_context'), \
             patch.object(signing, 'verify_backup', return_value=self.record), \
             patch.object(signing, 'store_release', side_effect=[dict(self.saved, assets=[]), self.saved]), \
             patch.object(signing, 'tool') as tool, patch.object(signing.control, 'api') as api:
            signing.save()
        args = tool.call_args.args[0]
        self.assertEqual(['gh', 'release', 'upload', signing.STORE_TAG], args[:4])
        self.assertEqual(str(self.root / 'backup/nuvix-signing-backup.zip'), args[4])
        self.assertNotIn('--clobber', args)
        self.assertEqual('PATCH', api.call_args.kwargs['method'])
        self.assertFalse(any('/actions/secrets' in c.args[0] or '/actions/variables' in c.args[0] for c in api.call_args_list))
        summary = Path(self.env['GITHUB_STEP_SUMMARY']).read_text()
        self.assertIn(self.record['fingerprint'], summary)
        for name in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD'):
            self.assertNotIn(self.record['secrets'][name], summary)

    def test_save_rejects_existing_key_or_changed_uploaded_bytes(self):
        with patch.dict(os.environ, self.env), patch.object(signing, 'validate_context'), \
             patch.object(signing, 'verify_backup', return_value=self.record), \
             patch.object(signing, 'store_release', return_value=self.saved), patch.object(signing, 'tool') as tool, \
             self.assertRaisesRegex(ValueError, 'refusing replacement'):
            signing.save()
        tool.assert_not_called()
        altered = dict(self.saved, assets=[dict(self.asset, digest='sha256:' + '0' * 64)])
        with patch.dict(os.environ, self.env), patch.object(signing, 'validate_context'), \
             patch.object(signing, 'verify_backup', return_value=self.record), \
             patch.object(signing, 'store_release', side_effect=[dict(self.saved, assets=[]), altered]), \
             patch.object(signing, 'tool'), patch.object(signing.control, 'api') as api, \
             self.assertRaisesRegex(ValueError, 'checksum differs'):
            signing.save()
        api.assert_not_called()

    def load(self, *, data=None):
        with patch.dict(os.environ, self.env), patch.object(signing, 'validate_context') as context, \
             patch.object(signing, 'store_release', return_value=self.saved), \
             patch.object(signing.control, 'api', return_value=self.data if data is None else data), \
             patch.object(signing, 'generate', side_effect=AssertionError('Release must never generate keys')):
            signing.load()
        context.assert_called_once_with(release=True)

    def test_load_exports_only_public_fingerprint_and_keeps_credentials_private(self):
        self.load()
        env_output = Path(self.env['GITHUB_ENV']).read_text()
        self.assertEqual(f"EXPECTED_SIGNING_CERT_SHA256={self.record['fingerprint']}\n", env_output)
        private = Path(self.temp.name) / 'nuvix-release-key/credentials.json'
        self.assertEqual(0o600, private.stat().st_mode & 0o777)
        self.assertEqual(self.record['fingerprint'], json.loads(private.read_text())['fingerprint'])
        for value in self.record['secrets'].values():
            self.assertNotIn(value, env_output)

    def test_load_rejects_wrong_password_changed_download_and_pinned_certificate(self):
        self.env['SIGNING_BACKUP_PASSWORD'] = 'incorrect-password'
        with self.assertRaises(Exception):
            self.load()
        self.assertFalse(Path(self.env['GITHUB_ENV']).exists())
        shutil.rmtree(Path(self.temp.name) / 'nuvix-release-key')
        self.env['SIGNING_BACKUP_PASSWORD'] = type(self).env['SIGNING_BACKUP_PASSWORD']
        with self.assertRaisesRegex(ValueError, 'checksum differs'):
            self.load(data=b'changed')
        self.env['EXPECTED_SIGNING_CERT_SHA256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'pinned identity'):
            self.load()
        self.assertFalse(Path(self.env['GITHUB_ENV']).exists())

    def test_complete_legacy_credentials_still_work_without_a_backup_password(self):
        with patch.dict(os.environ, dict(self.env, LEGACY_SIGNING_CONFIGURED='true',
                                        EXPECTED_SIGNING_CERT_SHA256='a' * 64, SIGNING_BACKUP_PASSWORD='')), \
             patch.object(signing, 'validate_context'), patch.object(signing, 'store_release') as store:
            signing.load()
        store.assert_not_called()

    def test_unexpected_private_errors_are_not_printed(self):
        with patch('sys.argv', ['production-signing.py', 'generate']), \
             patch.object(signing, 'generate', side_effect=RuntimeError('PRIVATE-CANARY')), self.assertRaises(SystemExit) as error:
            signing.main()
        self.assertNotIn('PRIVATE-CANARY', str(error.exception))


if __name__ == '__main__':
    unittest.main()
