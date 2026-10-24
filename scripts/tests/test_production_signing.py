"""First-key setup contracts and disposable-key backup recovery; no real secrets or API writes."""
import copy
import importlib.util
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('first_signing', ROOT / 'scripts/production-signing.py')
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

    def test_current_setup_workflow_is_valid(self):
        identity.check_signing_setup(self.workflow)
        identity.check_workflows()

    def test_setup_is_manual_main_only_and_serialized_with_releases(self):
        for event in ('push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_call'):
            self.reject(lambda w: w['on'].update({event: {}}))
        self.reject(lambda w: w['jobs']['setup'].update({'if': 'true'}))
        self.reject(lambda w: w['concurrency'].update(group='separate-from-releases'))
        self.reject(lambda w: w['concurrency'].update({'cancel-in-progress': 'true'}))
        self.reject(lambda w: w['permissions'].update(contents='write'))
        self.reject(lambda w: w['env'].update(TRUSTED_RELEASE_ACTORS='anyone'))

    def test_no_plaintext_backup_or_secret_in_dependencies(self):
        self.reject(lambda w: w['jobs']['setup']['steps'][5]['with'].update(path='${{ runner.temp }}/nuvix-first-signing/**'))
        self.reject(lambda w: w['jobs']['setup']['steps'][3].update(env={'TOKEN': '${{ secrets.SIGNING_SETUP_TOKEN }}'}))
        self.reject(lambda w: w['jobs']['setup'].update(env={'TOKEN': '${{ secrets.SIGNING_SETUP_TOKEN }}'}))

    def test_backup_cannot_be_skipped_or_installed_after_credentials(self):
        for index in (1, 4, 5, 6):
            self.reject(lambda w: w['jobs']['setup']['steps'][index].update({'if': 'false'}))
            self.reject(lambda w: w['jobs']['setup']['steps'][index].update({'continue-on-error': 'true'}))
        def reorder(w):
            steps = w['jobs']['setup']['steps']
            steps[5], steps[6] = steps[6], steps[5]
        self.reject(reorder)
        self.reject(lambda w: w['jobs']['setup']['steps'][6]['env'].pop('BACKUP_ARTIFACT_ID'))
        self.reject(lambda w: w['jobs']['setup']['steps'][7].pop('if'))
        self.reject(lambda w: w['jobs']['setup']['steps'].append({'run': 'gh release create v1.0.0'}))


class SetupPreflightTest(unittest.TestCase):
    def setUp(self):
        self.env = {'GITHUB_REPOSITORY': signing.control.REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40,
                    'GITHUB_RUN_ID': '10', 'GITHUB_ACTOR': 'DisabledAbel', 'GITHUB_TRIGGERING_ACTOR': 'DisabledAbel',
                    'GH_TOKEN': 'disposable-test-token', 'SIGNING_BACKUP_PASSWORD': 'disposable-backup-password-for-tests'}
        base = signing.control.BASE
        self.responses = {
            f'{base}/actions/runs/10': {'event': 'workflow_dispatch', 'head_branch': 'main', 'head_sha': 'a' * 40,
                'status': 'in_progress', 'conclusion': None, 'path': '.github/workflows/setup-production-signing.yml'},
            f'{base}/git/ref/heads/main': {'object': {'sha': 'a' * 40}},
            f'{base}/collaborators/DisabledAbel/permission': {'permission': 'admin'},
            f'{base}/actions/secrets?per_page=30&page=1': {'secrets': []},
            f'{base}/actions/variables?per_page=30&page=1': {'variables': []},
            f'{base}/releases?per_page=1': [],
        }

    def preflight(self):
        with patch.dict(os.environ, self.env, clear=True), \
             patch.object(signing.control, 'api', side_effect=lambda path: self.responses[path]), \
             patch.object(signing.control.release, 'run', return_value='a' * 40):
            signing.preflight()

    def test_first_identity_preflight_is_read_only(self):
        self.preflight()

    def test_missing_token_and_untrusted_context_stop_before_api(self):
        for key, value in [('GH_TOKEN', ''), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_RUN_ATTEMPT', '2'),
                           ('GITHUB_REPOSITORY', 'other/Nuvix')]:
            with self.subTest(key=key), patch.dict(os.environ, dict(self.env, **{key: value}), clear=True), \
                 patch.object(signing.control, 'api') as api, self.assertRaises(ValueError):
                signing.preflight()
            api.assert_not_called()

    def test_stale_main_and_other_workflow_are_rejected(self):
        self.responses[f'{signing.control.BASE}/git/ref/heads/main'] = {'object': {'sha': 'b' * 40}}
        with self.assertRaisesRegex(ValueError, 'main changed'):
            self.preflight()
        self.responses[f'{signing.control.BASE}/git/ref/heads/main'] = {'object': {'sha': 'a' * 40}}
        self.responses[f'{signing.control.BASE}/actions/runs/10']['path'] = '.github/workflows/release.yml'
        with self.assertRaisesRegex(ValueError, 'Unexpected signing'):
            self.preflight()

    def test_short_backup_password_is_rejected(self):
        self.env['SIGNING_BACKUP_PASSWORD'] = 'too-short'
        with self.assertRaisesRegex(ValueError, 'at least 24'):
            self.preflight()

    def test_existing_partial_identity_and_reservation_prevent_generation(self):
        for name in signing.SIGNING_NAMES | {signing.RESERVATION}:
            for kind in ('secrets', 'variables'):
                key = f'{signing.control.BASE}/actions/{kind}?per_page=30&page=1'
                self.responses[key] = {kind: [{'name': name}]}
                with self.subTest(name=name, kind=kind), self.assertRaises(ValueError):
                    self.preflight()
                self.responses[key] = {kind: []}
        self.responses[f'{signing.control.BASE}/releases?per_page=1'] = [{'draft': True}]
        with self.assertRaisesRegex(ValueError, 'release already exists'):
            self.preflight()

    def test_inventory_checks_later_pages_and_rejects_api_failure(self):
        with patch.object(signing.control, 'api', side_effect=[{'secrets': [{'name': 'OTHER'}] * 30},
                                                              {'secrets': [{'name': 'KEYSTORE_BASE64'}]}]):
            self.assertIn('KEYSTORE_BASE64', signing.inventory('secrets'))
        with patch.object(signing.control, 'api', side_effect=RuntimeError('denied')), self.assertRaises(ValueError):
            signing.inventory('secrets')
        with patch.object(signing.control, 'api', return_value={}), self.assertRaises(ValueError):
            signing.inventory('variables')

    def test_api_failure_reports_only_status_without_private_response(self):
        with patch.object(signing.control, 'api', side_effect=RuntimeError('PRIVATE-CANARY (HTTP 403)')):
            with self.assertRaises(ValueError) as error:
                signing.api(f'{signing.control.BASE}/actions/variables')
        self.assertIn('HTTP 403', str(error.exception))
        self.assertNotIn('PRIVATE-CANARY', str(error.exception))


class BackupAndInstallationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.fixture.cleanup)
        cls.env = {'RUNNER_TEMP': cls.fixture.name, 'GITHUB_RUN_ID': '10',
                   'SIGNING_BACKUP_PASSWORD': 'disposable-backup-password-for-tests'}
        with patch.dict(os.environ, cls.env), patch.object(signing, 'preflight'), patch.object(signing.control, 'api'):
            signing.generate()  # A disposable test key, deleted with the fixture; never a user identity.
        cls.fixture_root = Path(cls.fixture.name) / 'nuvix-first-signing'
        cls.record = signing.verify_backup(cls.fixture_root / 'backup/nuvix-signing-backup.zip', cls.env['SIGNING_BACKUP_PASSWORD'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'nuvix-first-signing'
        shutil.copytree(self.fixture_root, self.root)
        self.env = dict(type(self).env, RUNNER_TEMP=self.temp.name, BACKUP_ARTIFACT_ID='7',
                        GITHUB_STEP_SUMMARY=str(Path(self.temp.name) / 'summary'))
        self.artifact = {'name': 'nuvix-first-signing-backup-10', 'workflow_run': {'id': 10},
                         'expired': False, 'size_in_bytes': 1024}

    def api(self, path, **kwargs):
        if path.endswith('/actions/artifacts/7'):
            return self.artifact
        if path.endswith('/actions/variables/' + signing.RESERVATION):
            return {'value': '10'}
        if path.endswith('/actions/variables'):
            return {}
        raise AssertionError('Unexpected API operation')

    def install_patches(self):
        from contextlib import ExitStack
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.dict(os.environ, self.env))
        stack.enter_context(patch.object(signing, 'validate_context'))
        stack.enter_context(patch.object(signing, 'verify_backup', return_value=self.record))
        stack.enter_context(patch.object(signing, 'check_absent_identity', return_value={signing.RESERVATION}))
        stack.enter_context(patch.object(signing, 'inventory', return_value=set()))
        api = stack.enter_context(patch.object(signing.control, 'api', side_effect=self.api))
        tool = stack.enter_context(patch.object(signing, 'tool'))
        return api, tool

    def test_encrypted_backup_restores_same_key_and_credentials(self):
        record = signing.verify_backup(self.root / 'backup/nuvix-signing-backup.zip', self.env['SIGNING_BACKUP_PASSWORD'])
        self.assertEqual(self.record['fingerprint'], record['fingerprint'])
        self.assertEqual(signing.SIGNING_NAMES - {'SIGNING_CERT_SHA256'}, set(record['secrets']))
        with self.assertRaises(Exception):
            signing.verify_backup(self.root / 'backup/nuvix-signing-backup.zip', 'incorrect-password')

    def test_generation_reserves_identity_before_invoking_keytool(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, dict(self.env, RUNNER_TEMP=directory)), \
             patch.object(signing, 'preflight'), patch.object(signing.control, 'api') as api:
            def fail(args, **kwargs):
                self.assertEqual('POST', api.call_args.kwargs['method'])
                self.assertEqual(signing.RESERVATION, api.call_args.kwargs['data']['name'])
                raise ValueError('keytool unavailable')
            with patch.object(signing, 'tool', side_effect=fail), self.assertRaises(ValueError):
                signing.generate()

    def test_install_requires_successful_artifact_and_uses_stdin_for_all_secrets(self):
        api, tool = self.install_patches()
        signing.install()
        calls = tool.call_args_list
        self.assertEqual(4, len(calls))
        for call in calls:
            args = call.args[0]
            self.assertEqual(['gh', 'secret', 'set'], args[:3])
            self.assertIn(args[3], self.record['secrets'])
            self.assertIsInstance(call.kwargs['data'], bytes)
            self.assertNotIn(call.kwargs['data'].decode(), args)
        self.assertEqual('SIGNING_CERT_SHA256', api.call_args.kwargs['data']['name'])
        summary = Path(self.env['GITHUB_STEP_SUMMARY']).read_text()
        self.assertIn(self.record['fingerprint'], summary)
        for name in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD'):
            self.assertNotIn(self.record['secrets'][name], summary)

    def test_missing_wrong_expired_or_other_run_artifact_prevents_secret_writes(self):
        _, tool = self.install_patches()
        for change in ({'name': 'other'}, {'workflow_run': {'id': 11}}, {'expired': True}, {'size_in_bytes': 0}):
            original = self.artifact
            self.artifact = dict(original, **change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                signing.install()
            tool.assert_not_called()
            self.artifact = original
        with patch.dict(os.environ, {'BACKUP_ARTIFACT_ID': ''}), self.assertRaises(ValueError):
            signing.install()
        tool.assert_not_called()

    def test_changed_backup_or_existing_secret_prevents_replacement(self):
        _, tool = self.install_patches()
        (self.root / 'backup.sha256').write_text('0' * 64)
        with self.assertRaisesRegex(ValueError, 'missing or changed'):
            signing.install()
        tool.assert_not_called()
        shutil.copyfile(self.fixture_root / 'backup.sha256', self.root / 'backup.sha256')
        with patch.object(signing, 'inventory', return_value={'KEYSTORE_BASE64'}), self.assertRaisesRegex(ValueError, 'refusing replacement'):
            signing.install()
        tool.assert_not_called()

    def test_unexpected_errors_with_private_material_are_not_printed(self):
        with patch('sys.argv', ['production-signing.py', 'generate']), \
             patch.object(signing, 'generate', side_effect=RuntimeError('PRIVATE-CANARY')), \
             self.assertRaises(SystemExit) as error:
            signing.main()
        self.assertNotIn('PRIVATE-CANARY', str(error.exception))


if __name__ == '__main__':
    unittest.main()
