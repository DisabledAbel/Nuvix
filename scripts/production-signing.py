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
STORE_NAME = 'Nuvix encrypted signing backup — keep as draft'


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


def store_release(*, release_id=None):
    """Only a receipt/configured numeric ID or the expected tag can identify a store.

    Titles and filenames are hints for diagnostics/conflict detection, never authority
    to recover a differently tagged draft. The encrypted identity is checked by load().
    """
    selected = str(release_id) if release_id is not None else os.environ.get('SIGNING_STORE_RELEASE_ID', '')
    require(not selected or re.fullmatch(r'[1-9][0-9]*', selected, re.ASCII),
            'Invalid SIGNING_STORE_RELEASE_ID; use the numeric ID of the existing signing draft')
    releases = control.pages(f'{control.BASE}/releases')
    matches = [item for item in releases if
               (selected and item.get('id') == int(selected)) or item.get('tag_name') == STORE_TAG or
               item.get('name') == STORE_NAME or
               any(asset.get('name') == STORE_ASSET for asset in item.get('assets', []))]
    require(len(matches) <= 1,
            'Ambiguous permanent signing backup: multiple candidates; inspect the signing drafts without replacing any key')
    require(matches and (not selected or matches[0].get('id') == int(selected)),
            'Permanent signing backup missing; inspect Setup Production Signing and SIGNING_STORE_RELEASE_ID; never generate a replacement')
    saved = matches[0]
    require(type(saved.get('id')) is int and saved['id'] > 0 and saved.get('draft') is True and
            saved.get('published_at') is None,
            'Invalid permanent signing store: it must remain an unpublished draft')
    require(selected or saved.get('tag_name') == STORE_TAG,
            'Permanent signing backup has an unexpected tag; verify the existing draft and configure SIGNING_STORE_RELEASE_ID; never generate a replacement')
    return saved


def store_asset(release):
    assets = release.get('assets', [])
    require(isinstance(assets, list) and len(assets) == 1 and isinstance(assets[0], dict) and
            type(assets[0].get('id')) is int and assets[0]['id'] > 0 and
            assets[0].get('name') == STORE_ASSET and
            assets[0].get('state') == 'uploaded' and type(assets[0].get('size')) is int and
            1 <= assets[0]['size'] <= 1024 * 1024 and isinstance(assets[0].get('digest'), str) and
            re.fullmatch(r'sha256:[a-f0-9]{64}', assets[0].get('digest', '')),
            'Encrypted signing backup is incomplete; inspect the setup run instead of creating a new key')
    return assets[0]


def preflight():
    validate_context()
    password = os.environ.get('SIGNING_BACKUP_PASSWORD', '')
    require(len(password) >= 24 and not password.isspace(),
            'Add SIGNING_BACKUP_PASSWORD as a repository secret: at least 24 characters, saved privately outside GitHub')
    confirmation = os.environ.get('LOST_RC1_KEY_CONFIRMATION', '')
    if confirmation:
        require(confirmation == 'REPLACE LOST RC1 KEY; REINSTALL REQUIRED',
                'Confirm RC1 data loss with the exact documented replacement acknowledgment')
        require(os.environ.get('LEGACY_SIGNING_PRESENT') == 'false',
                'Manual signing credentials still exist; recover them instead of replacing a key')
        require(os.environ.get('SIGNING_STORE_RELEASE_ID', '') in ('', str(control.LOST_RC1_STORE_ID)),
                'Unexpected signing-store ID; inspect it without replacing any key')
        require(not os.environ.get('PINNED_SIGNING_CERT_SHA256'),
                'A certificate pin is configured; review and remove only the lost RC1 pin before replacement')
        # Require both direct lookup and complete authenticated inventory to prove absence.
        require(control.api(f'{control.BASE}/releases/{control.LOST_RC1_STORE_ID}', missing=True) is None,
                'Original signing store still exists; recover it instead of replacing a key')
        history = control.published_history()
        require(len(history) == 1 and control.is_lost_rc1(*history[0]),
                'Replacement is limited to the exact RC1-only history with no surviving drafts')
    else:
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
        'name': STORE_NAME, 'draft': True,
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
    reserved = store_release(release_id=receipt['release_id'])
    require(reserved['id'] == receipt['release_id'] and not reserved.get('assets'),
            'Signing store changed or already has a key; refusing replacement')
    # Address the reserved release directly; a draft's tag is mutable. gh uses
    # GH_ENTERPRISE_TOKEN for hosts other than github.com, including uploads.
    tool(['gh', 'api', '--hostname', 'uploads.github.com',
          f"https://uploads.github.com/{control.BASE}/releases/{reserved['id']}/assets?name={STORE_ASSET}",
          '--method', 'POST', '-H', 'Content-Type: application/zip', '--input', str(backup)],
         env=dict(os.environ, GH_ENTERPRISE_TOKEN=os.environ['GH_TOKEN']))
    uploaded = store_release(release_id=receipt['release_id'])
    asset = store_asset(uploaded)
    require(uploaded['id'] == receipt['release_id'] and
            asset['digest'] == 'sha256:' + hashlib.sha256(backup.read_bytes()).hexdigest(),
            'Uploaded signing backup checksum differs')
    api(f"{control.BASE}/releases/{reserved['id']}", method='PATCH', data={
        'tag_name': reserved['tag_name'], 'draft': True,
        'body': f"Permanent AES-256 encrypted signing backup. Certificate SHA-256: `{record['fingerprint']}`. "
                'Keep this draft and the separately saved SIGNING_BACKUP_PASSWORD. Download an offline backup. '
                'This is key storage, not an APK release; never publish this draft.'})
    completed = store_release(release_id=receipt['release_id'])
    completed_asset = store_asset(completed)
    require(all(completed_asset[key] == asset[key] for key in ('id', 'size', 'digest')),
            'Signing backup changed while completing setup')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f"Permanent signing key ready. Certificate SHA-256: `{record['fingerprint']}`.\n\n"
                      f"Signing store release ID: `{completed['id']}`. Save this public ID as the repository variable "
                      '`SIGNING_STORE_RELEASE_ID` for durable lookup even if GitHub changes the draft tag.\n\n'
                      f"[Download an encrypted backup]({completed['html_url']}) and save your password separately. "
                      'The backup stays in the signing draft without Actions artifact expiration. '
                      'Download the backup before running Release. If replacing the lost RC1 key, RC1 users must reinstall; '
                      'no in-place upgrade or data retention is possible. No APK was published.\n')


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
