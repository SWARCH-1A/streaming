"""Group 2: private lab CA and Core PKCS#12 keystore with OpenSSL.

Runs inside the disposable tools container (`lab.py certs`), so every participant
uses the same OpenSSL build regardless of the host operating system.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

CORE_UID = 10001  # USER of services/core/Dockerfile; must read the mounted keystore.
SUBJECT_ALT_NAMES = 'DNS:core-lab,DNS:localhost,IP:127.0.0.1'
DAYS = 30  # Short-lived: laboratory material, never product certificates.
CA_SUBJECT = '/O=Vault tv Lab5/CN=Vault tv Lab5 private CA'
SERVER_SUBJECT = '/O=Vault tv Lab5/CN=core-lab'
SERVER_EXTENSIONS = f"""basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = {SUBJECT_ALT_NAMES}
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid, issuer
"""


def openssl(*arguments):
    result = subprocess.run(['openssl', *map(str, arguments)], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'openssl {arguments[0]} failed: {result.stderr.strip()}')
    return result.stdout


def new_key(path):
    """EC P-256 private key (PEM, unencrypted, only inside private folders)."""
    openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', path)
    os.chmod(path, 0o600)


def create_ca(pki):
    key, certificate = pki / 'ca.key', pki / 'ca.crt'
    new_key(key)
    openssl('req', '-x509', '-new', '-key', key, '-sha256', '-days', DAYS, '-subj', CA_SUBJECT,
            '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
            '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
            '-addext', 'subjectKeyIdentifier=hash', '-out', certificate)


def issue_server(pki, work):
    key, request = work / 'core.key', work / 'core.csr'
    certificate, extensions = work / 'core.crt', work / 'core.ext'
    new_key(key)
    openssl('req', '-new', '-key', key, '-subj', SERVER_SUBJECT, '-out', request)
    extensions.write_text(SERVER_EXTENSIONS, encoding='utf-8')
    openssl('x509', '-req', '-in', request, '-CA', pki / 'ca.crt', '-CAkey', pki / 'ca.key',
            '-set_serial', '0x' + os.urandom(16).hex(), '-sha256', '-days', DAYS,
            '-extfile', extensions, '-out', certificate)
    # Refuse to publish material that a strict TLS client would reject.
    for host in ('core-lab', 'localhost'):
        openssl('verify', '-CAfile', pki / 'ca.crt', '-purpose', 'sslserver',
                '-verify_hostname', host, certificate)
    openssl('verify', '-CAfile', pki / 'ca.crt', '-purpose', 'sslserver',
            '-verify_ip', '127.0.0.1', certificate)
    return key, certificate


def publish(source, target, mode, owner=None):
    """Atomic replacement inside the mounted folder; permissions set before exposure."""
    temporary = target.with_name('.' + target.name + '.tmp')
    temporary.write_bytes(source.read_bytes())
    os.chmod(temporary, mode)
    if owner is not None:
        os.chown(temporary, owner, owner)
    os.replace(temporary, target)


def describe(tls):
    details = openssl('x509', '-in', tls / 'core.crt', '-noout', '-subject', '-issuer',
                      '-enddate', '-fingerprint', '-sha256', '-ext', 'subjectAltName')
    return '\n'.join(line.rstrip() for line in details.splitlines() if line.strip())


def generate(state, *, force=False, owner=CORE_UID):
    tls, pki = state / 'tls', state / 'pki'
    password = tls / 'keystore-password.txt'
    if not password.is_file() or not password.read_text(encoding='utf-8').strip():
        raise RuntimeError('Missing keystore password; run lab.py certs from the host.')
    if (tls / 'core.p12').exists() and not force:
        print('TLS material already exists and was preserved (use --force to reissue).')
        print(describe(tls))
        return
    pki.mkdir(mode=0o700, exist_ok=True)
    if force or not (pki / 'ca.key').exists():
        create_ca(pki)
    with tempfile.TemporaryDirectory() as folder:
        work = Path(folder)
        key, certificate = issue_server(pki, work)
        keystore = work / 'core.p12'
        openssl('pkcs12', '-export', '-name', 'core-lab', '-inkey', key, '-in', certificate,
                '-certfile', pki / 'ca.crt', '-passout', f'file:{password}', '-out', keystore)
        openssl('pkcs12', '-in', keystore, '-noout', '-passin', f'file:{password}')
        publish(pki / 'ca.crt', tls / 'ca.crt', 0o644)
        publish(certificate, tls / 'core.crt', 0o644)
        publish(keystore, tls / 'core.p12', 0o400, owner)
    print('Issued private CA and Core keystore (.state/tls/core.p12, readable by UID 10001).')
    print(describe(tls))


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--force', action='store_true', help='reissue CA and server certificate')
    args = parser.parse_args()
    try:
        generate(Path('/state'), force=args.force)
    except RuntimeError as error:
        raise SystemExit(str(error))
