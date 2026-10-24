#!/usr/bin/env python3
"""Permanent encrypted signing-key storage using one password and the built-in GitHub token."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('signing_control', ROOT / 'scripts/release-control.py')
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)
class SetupError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise SetupError(message)
SIGNING_NAMES = {'KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'SIGNING_CERT_SHA256'}
STORE_TAG = 'nuvix-signing-key'
STORE_ASSET = 'nuvix-signing-backup.zip'


def api(path, **kwargs):
    try:
        return control.api(path, **kwargs)
    except RuntimeError as error:
        status = re.search(r'\(HTTP ([0-9]{3})\)', str(error))
        detail = f' (HTTP {status[1]})' if status else ''
        raise SetupError(f'GitHub signing setup API failed{detail}; check the workflow contents permission') from None


def paths():
    root = Path(os.environ['RUNNER_TEMP']) / 'nuvix-first-signing'
    return root, root / 'backup/nuvix-signing-backup.zip'


def tool(args, *, env=None, data=None):
    result = subprocess.run(args, env=env, input=data, capture_output=True)
    require(result.returncode == 0, 'Signing setup tool failed; private output withheld')
    return result.stdout


def validate_context(*, release=False):
    env = os.environ
    require(env.get('GH_TOKEN'), 'GitHub workflow token is unavailable')
    if not release:
        require(env.get('GITHUB_REPOSITORY') == control.REPO and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
                and env.get('GITHUB_REF') == 'refs/heads/main' and env.get('GITHUB_RUN_ATTEMPT') == '1',
                'Signing setup requires a fresh manual run on this repository main')
    run = api(f"{control.BASE}/actions/runs/{int(env['GITHUB_RUN_ID'])}")
    control.check_context(env, run)
    expected = '.github/workflows/release.yml' if release else '.github/workflows/setup-production-signing.yml'
    require(run.get('path') == expected, 'Unexpected signing workflow')
    control.authorize_publisher(env)
    head = api(f'{control.BASE}/git/ref/heads/main')['object']['sha']
    require(head == env['GITHUB_SHA'] == control.release.run('git', 'rev-parse', 'HEAD').strip(),
            'main changed; signing must use exact current main')


def store_release():
    matches = [item for item in control.pages(f'{control.BASE}/releases') if item.get('tag_name') == STORE_TAG]
    require(len(matches) == 1 and matches[0].get('draft') is True,
            'Permanent signing backup missing or ambiguous; inspect Setup Production Signing')
    return matches[0]


def store_asset(release):
    assets = release.get('assets', [])
    require(len(assets) == 1 and assets[0].get('name') == STORE_ASSET and
            assets[0].get('state') == 'uploaded' and 1 <= assets[0].get('size', 0) <= 1024 * 1024 and
            re.fullmatch(r'sha256:[a-f0-9]{64}', assets[0].get('digest', '')),
            'Encrypted signing backup is incomplete; inspect the setup run instead of creating a new key')
    return assets[0]


def preflight():
    validate_context()
    password = os.environ.get('SIGNING_BACKUP_PASSWORD', '')
    require(len(password) >= 24 and not password.isspace(),
            'Add SIGNING_BACKUP_PASSWORD as a repository secret: at least 24 characters, saved privately outside GitHub')
    require(os.environ.get('EXISTING_SIGNING_IDENTITY') == 'false',
            'Signing credentials already exist or are partial; preserve the existing key')
    require(not control.pages(f'{control.BASE}/releases'),
            'A release or signing backup already exists; use the permanent key instead of generating another')



def verify_backup(backup, password, *, exercise=True):
    import pyzipper
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import pkcs12
    with pyzipper.AESZipFile(backup) as archive:
        archive.setpassword(password.encode())
        require(set(archive.namelist()) == {'nuvix-release.jks', 'credentials.json', 'certificate.pem'},
                'Unexpected signing backup contents')
        record = json.loads(archive.read('credentials.json'))
        key_bytes = archive.read('nuvix-release.jks')
        cert = x509.load_pem_x509_certificate(archive.read('certificate.pem'))
    require(isinstance(record, dict) and isinstance(record.get('secrets'), dict) and
            set(record['secrets']) == SIGNING_NAMES - {'SIGNING_CERT_SHA256'} and
            all(isinstance(value, str) and value for value in record['secrets'].values()),
            'Invalid signing backup credential set')
    fingerprint = cert.fingerprint(hashes.SHA256()).hex()
    require(record['fingerprint'] == fingerprint and
            base64.b64decode(record['secrets']['KEYSTORE_BASE64'], validate=True) == key_bytes,
            'Signing backup identity does not match its credentials')
    if not exercise:
        return record
    with tempfile.TemporaryDirectory(dir=backup.parent) as directory:
        restored, p12 = Path(directory) / 'restored.jks', Path(directory) / 'restored.p12'
        restored.write_bytes(key_bytes)
        verify_password = secrets.token_urlsafe(32)
        env = dict(os.environ, NUVIX_STORE_PASSWORD=record['secrets']['KEYSTORE_PASSWORD'],
                   NUVIX_KEY_PASSWORD=record['secrets']['KEY_PASSWORD'], NUVIX_VERIFY_PASSWORD=verify_password)
        tool(['keytool', '-importkeystore', '-noprompt', '-srckeystore', str(restored), '-srcstoretype', 'JKS',
              '-srcalias', record['secrets']['KEY_ALIAS'], '-srcstorepass:env', 'NUVIX_STORE_PASSWORD',
              '-srckeypass:env', 'NUVIX_KEY_PASSWORD', '-destkeystore', str(p12), '-deststoretype', 'PKCS12',
              '-deststorepass:env', 'NUVIX_VERIFY_PASSWORD', '-destkeypass:env', 'NUVIX_VERIFY_PASSWORD'], env=env)
        key, recovered, _ = pkcs12.load_key_and_certificates(p12.read_bytes(), verify_password.encode())
        require(recovered.fingerprint(hashes.SHA256()).hex() == fingerprint, 'Recovered certificate differs')
        challenge = b'Nuvix first signing backup recovery check'
        signature = key.sign(challenge, padding.PKCS1v15(), hashes.SHA256())
        cert.public_key().verify(signature, challenge, padding.PKCS1v15(), hashes.SHA256())
    return record


def generate():
    preflight()
    os.umask(0o077)
    root, backup = paths()
    require(not root.exists(), 'Local signing setup already exists; refusing replacement')
    # Reserve a permanent draft before generation; no secret-management API or PAT is needed.
    reserved = api(f'{control.BASE}/releases', method='POST', data={
        'tag_name': STORE_TAG, 'target_commitish': os.environ['GITHUB_SHA'],
        'name': 'Nuvix encrypted signing backup — keep as draft', 'draft': True,
        'prerelease': True, 'make_latest': 'false',
        'body': 'Signing setup is in progress. Keep this backup as a draft; do not publish or delete it.'})
    require(type(reserved.get('id')) is int and reserved['id'] > 0, 'Cannot reserve the signing identity')
    root.mkdir(mode=0o700)
    backup.parent.mkdir(mode=0o700)
    (root / 'reservation.json').write_text(json.dumps({'release_id': reserved['id'],
        'run_id': os.environ['GITHUB_RUN_ID']}))
    store_password, key_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    env = dict(os.environ, NUVIX_STORE_PASSWORD=store_password, NUVIX_KEY_PASSWORD=key_password)
    keystore = root / 'nuvix-release.jks'
    tool(['keytool', '-genkeypair', '-noprompt', '-keystore', str(keystore), '-storetype', 'JKS',
          '-alias', 'nuvix-release', '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
          '-validity', '10950', '-dname', 'CN=Nuvix Release, OU=Android, O=Nuvix',
          '-storepass:env', 'NUVIX_STORE_PASSWORD', '-keypass:env', 'NUVIX_KEY_PASSWORD'], env=env)
    der = tool(['keytool', '-exportcert', '-keystore', str(keystore), '-alias', 'nuvix-release',
                '-storepass:env', 'NUVIX_STORE_PASSWORD'], env=env)
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    certificate = x509.load_der_x509_certificate(der)
    record = {'repository': control.REPO, 'run_id': os.environ['GITHUB_RUN_ID'],
              'fingerprint': certificate.fingerprint(hashes.SHA256()).hex(), 'secrets': {
                  'KEYSTORE_BASE64': base64.b64encode(keystore.read_bytes()).decode(),
                  'KEYSTORE_PASSWORD': store_password, 'KEY_ALIAS': 'nuvix-release', 'KEY_PASSWORD': key_password}}
    import pyzipper
    with pyzipper.AESZipFile(backup, 'x', compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as archive:
        archive.setpassword(os.environ['SIGNING_BACKUP_PASSWORD'].encode())
        archive.setencryption(pyzipper.WZ_AES, nbits=256)
        archive.writestr('nuvix-release.jks', keystore.read_bytes())
        archive.writestr('credentials.json', json.dumps(record))
        archive.writestr('certificate.pem', certificate.public_bytes(serialization.Encoding.PEM))
    require(verify_backup(backup, os.environ['SIGNING_BACKUP_PASSWORD']) == record, 'Backup restore failed')
    # This local receipt binds permanent storage to the encrypted file verified here.
    (root / 'backup.sha256').write_text(hashlib.sha256(backup.read_bytes()).hexdigest())


def save():
    validate_context()
    root, backup = paths()
    require(backup.is_file() and hashlib.sha256(backup.read_bytes()).hexdigest() == (root / 'backup.sha256').read_text(),
            'Verified encrypted signing backup is missing or changed')
    record = verify_backup(backup, os.environ['SIGNING_BACKUP_PASSWORD'])
    receipt = json.loads((root / 'reservation.json').read_text())
    require(record['repository'] == control.REPO and record['run_id'] == receipt['run_id'] == os.environ['GITHUB_RUN_ID'],
            'Signing backup belongs to another repository or run')
    reserved = store_release()
    require(reserved['id'] == receipt['release_id'] and not reserved.get('assets'),
            'Signing store changed or already has a key; refusing replacement')
    tool(['gh', 'release', 'upload', STORE_TAG, str(backup), '--repo', control.REPO])
    uploaded = store_release()
    asset = store_asset(uploaded)
    require(uploaded['id'] == receipt['release_id'] and
            asset['digest'] == 'sha256:' + hashlib.sha256(backup.read_bytes()).hexdigest(),
            'Uploaded signing backup checksum differs')
    api(f"{control.BASE}/releases/{reserved['id']}", method='PATCH', data={
        'body': f"Permanent AES-256 encrypted signing backup. Certificate SHA-256: `{record['fingerprint']}`. "
                'Keep this draft and the separately saved SIGNING_BACKUP_PASSWORD. Download an offline backup. '
                'This is key storage, not an APK release; never publish this draft.'})
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f"Permanent signing key ready. Certificate SHA-256: `{record['fingerprint']}`.\n\n"
                      f"[Download an encrypted backup]({uploaded['html_url']}) and save your password separately. "
                      'The backup stays in the signing draft without Actions artifact expiration. '
                      'You can now run Release. No APK was published.\n')


def load():
    """Load the authenticated permanent key for one publishing job; never generate a key."""
    validate_context(release=True)
    baseline = os.environ.get('EXPECTED_SIGNING_CERT_SHA256', '')
    if os.environ.get('LEGACY_SIGNING_CONFIGURED') == 'true':
        require(re.fullmatch(r'[a-fA-F0-9]{64}', baseline), 'Existing signing secrets need SIGNING_CERT_SHA256')
        return
    password = os.environ.get('SIGNING_BACKUP_PASSWORD', '')
    require(password, 'Add the saved SIGNING_BACKUP_PASSWORD repository secret')
    saved = store_release()
    asset = store_asset(saved)
    data = api(f"{control.BASE}/releases/assets/{int(asset['id'])}", binary=True)
    require(len(data) == asset['size'] and 'sha256:' + hashlib.sha256(data).hexdigest() == asset['digest'],
            'Downloaded signing backup checksum differs')
    os.umask(0o077)
    root = Path(os.environ['RUNNER_TEMP']) / 'nuvix-release-key'
    require(not root.exists(), 'A signing key is already loaded in this job')
    root.mkdir(mode=0o700)
    backup = root / STORE_ASSET
    backup.write_bytes(data)
    record = verify_backup(backup, password, exercise=False)
    require(record.get('repository') == control.REPO and
            (not baseline or baseline.lower() == record['fingerprint']), 'Signing backup certificate differs from the pinned identity')
    (root / 'credentials.json').write_text(json.dumps(record))
    # Only a public fingerprint crosses into subsequent steps; passwords stay in a private file.
    with open(os.environ['GITHUB_ENV'], 'a') as stream:
        stream.write(f"EXPECTED_SIGNING_CERT_SHA256={record['fingerprint']}\n")


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'generate', 'save', 'load'))
    args = parser.parse_args()
    try:
        {'preflight': preflight, 'generate': generate, 'save': save, 'load': load}[args.command]()
    except SetupError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        raise SystemExit('Signing setup stopped. Check prerequisite secrets, publisher access, existing signing identity '
                         'and the permanent signing draft. Private tool output is withheld. Never clear existing '
                         'signing secrets to force regeneration; recover the same key from its encrypted backup.') from None


if __name__ == '__main__':
    main()
