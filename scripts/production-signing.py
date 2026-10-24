#!/usr/bin/env python3
"""One-time first-release key setup with an encrypted, verified backup before installation."""
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
RESERVATION = 'SIGNING_SETUP_RUN_ID'


def api(path, **kwargs):
    try:
        return control.api(path, **kwargs)
    except RuntimeError as error:
        status = re.search(r'\(HTTP ([0-9]{3})\)', str(error))
        detail = f' (HTTP {status[1]})' if status else ''
        raise SetupError(f'GitHub signing setup API failed{detail}; check the setup token permissions') from None


def paths():
    root = Path(os.environ['RUNNER_TEMP']) / 'nuvix-first-signing'
    return root, root / 'backup/nuvix-signing-backup.zip'


def tool(args, *, env=None, data=None):
    result = subprocess.run(args, env=env, input=data, capture_output=True)
    require(result.returncode == 0, 'Signing setup tool failed; private output withheld')
    return result.stdout


def inventory(kind):
    names = set()
    for page in range(1, 1001):
        # Repository variables have a documented maximum page size of 30.
        result = api(f'{control.BASE}/actions/{kind}?per_page=30&page={page}')
        items = result.get(kind)
        require(isinstance(items, list) and all(isinstance(i.get('name'), str) for i in items),
                'Cannot verify repository signing inventory')
        names.update(item['name'] for item in items)
        if len(items) < 30:
            return names
    raise ValueError('Signing inventory pagination limit exceeded')


def validate_context():
    env = os.environ
    require(env.get('GH_TOKEN'), 'Add the SIGNING_SETUP_TOKEN repository secret in GitHub Settings')
    require(env.get('GITHUB_REPOSITORY') == control.REPO and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_REF') == 'refs/heads/main' and env.get('GITHUB_RUN_ATTEMPT') == '1',
            'Signing setup requires a fresh manual run on this repository main')
    run = api(f"{control.BASE}/actions/runs/{int(env['GITHUB_RUN_ID'])}")
    control.check_context(env, run)
    require(run.get('path') == '.github/workflows/setup-production-signing.yml', 'Unexpected signing setup workflow')
    control.authorize_publisher(env)
    head = api(f'{control.BASE}/git/ref/heads/main')['object']['sha']
    require(head == env['GITHUB_SHA'] == control.release.run('git', 'rev-parse', 'HEAD').strip(),
            'main changed; signing setup must use exact current main')


def check_absent_identity():
    names = inventory('secrets') | inventory('variables')
    require(not names.intersection(SIGNING_NAMES),
            'Signing credentials already exist or are partial; restore the existing key instead of generating another')
    releases = api(f'{control.BASE}/releases?per_page=1')
    require(isinstance(releases, list) and not releases,
            'A release already exists; recover its existing signing identity')
    return names


def preflight():
    validate_context()
    password = os.environ.get('SIGNING_BACKUP_PASSWORD', '')
    require(len(password) >= 24 and not password.isspace(),
            'Add SIGNING_BACKUP_PASSWORD as a repository secret: at least 24 characters, saved privately outside GitHub')
    names = check_absent_identity()
    require(RESERVATION not in names,
            'A signing setup run already reserved this identity; inspect that run and recover its encrypted backup')


def verify_backup(backup, password):
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
    # Reserve before generation. Failures never silently produce a second identity.
    api(f'{control.BASE}/actions/variables', method='POST',
                data={'name': RESERVATION, 'value': os.environ['GITHUB_RUN_ID']})
    root.mkdir(mode=0o700)
    backup.parent.mkdir(mode=0o700)
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
    # This local receipt binds installation to the file actually uploaded by the next step.
    (root / 'backup.sha256').write_text(hashlib.sha256(backup.read_bytes()).hexdigest())


def install():
    validate_context()
    root, backup = paths()
    require(backup.is_file() and hashlib.sha256(backup.read_bytes()).hexdigest() == (root / 'backup.sha256').read_text(),
            'Verified encrypted signing backup is missing or changed')
    record = verify_backup(backup, os.environ['SIGNING_BACKUP_PASSWORD'])
    require(record['repository'] == control.REPO and record['run_id'] == os.environ['GITHUB_RUN_ID'],
            'Signing backup belongs to another repository or run')
    artifact_id = os.environ.get('BACKUP_ARTIFACT_ID', '')
    require(re.fullmatch(r'[1-9][0-9]*', artifact_id), 'Encrypted backup must upload successfully before installing secrets')
    artifact = api(f'{control.BASE}/actions/artifacts/{artifact_id}')
    require(artifact.get('name') == f"nuvix-first-signing-backup-{record['run_id']}" and
            artifact.get('workflow_run', {}).get('id') == int(record['run_id']) and
            artifact.get('expired') is False and artifact.get('size_in_bytes', 0) > 0,
            'Cannot verify this run uploaded its encrypted signing backup')
    names = check_absent_identity()
    require(RESERVATION in names and api(f'{control.BASE}/actions/variables/{RESERVATION}').get('value') == record['run_id'],
            'Signing identity reservation does not belong to this run')
    for name, value in record['secrets'].items():
        require(name not in inventory('secrets'), 'Signing secret appeared during setup; refusing replacement')
        tool(['gh', 'secret', 'set', name, '--repo', control.REPO], data=value.encode())
    api(f'{control.BASE}/actions/variables', method='POST',
                data={'name': 'SIGNING_CERT_SHA256', 'value': record['fingerprint']})
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f"Permanent Nuvix signing credentials installed. Certificate SHA-256: `{record['fingerprint']}`.\n\n"
                      'Download this run\'s encrypted signing-backup artifact within 7 days and keep it with your '
                      'separately saved backup password. Remove/revoke the setup token after saving the backup. '
                      'No APK or release was published.\n')


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'generate', 'install'))
    args = parser.parse_args()
    try:
        {'preflight': preflight, 'generate': generate, 'install': install}[args.command]()
    except SetupError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        raise SystemExit('Signing setup stopped. Check prerequisite secrets, publisher access, existing signing identity '
                         'and this run\'s backup artifact. Private tool output is withheld. Never clear existing '
                         'signing secrets to force regeneration; recover the same key from its encrypted backup.') from None


if __name__ == '__main__':
    main()
