"""Group 2 material checks with the host OpenSSL; no Docker daemon required."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import json
import lab
import pki


def openssl(*arguments, check=True):
    return subprocess.run(['openssl', *map(str, arguments)], capture_output=True, text=True, check=check)


@unittest.skipUnless(shutil.which('openssl'), 'OpenSSL not installed on host')
class CertificateTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.state = Path(self.folder.name)
        (self.state / 'tls').mkdir()
        (self.state / 'tls' / 'keystore-password.txt').write_text('fictitious-test-password')
        pki.generate(self.state, owner=None)
        self.tls = self.state / 'tls'

    def tearDown(self):
        self.folder.cleanup()

    def test_server_certificate_chains_to_private_ca_for_every_lab_name(self):
        for option, name in (('-verify_hostname', 'core-lab'), ('-verify_hostname', 'localhost'),
                             ('-verify_ip', '127.0.0.1')):
            openssl('verify', '-CAfile', self.tls / 'ca.crt', '-purpose', 'sslserver',
                    option, name, self.tls / 'core.crt')

    def test_wrong_name_is_rejected(self):
        result = openssl('verify', '-CAfile', self.tls / 'ca.crt', '-verify_hostname',
                         'attacker.example.test', self.tls / 'core.crt', check=False)
        self.assertNotEqual(0, result.returncode)

    def test_keystore_holds_key_and_chain_under_password(self):
        password = 'file:' + str(self.tls / 'keystore-password.txt')
        listing = openssl('pkcs12', '-in', self.tls / 'core.p12', '-passin', password,
                          '-nodes').stdout
        self.assertEqual(2, listing.count('BEGIN CERTIFICATE'))
        self.assertIn('PRIVATE KEY', listing)
        wrong = openssl('pkcs12', '-in', self.tls / 'core.p12', '-noout', '-passin', 'pass:wrong',
                        check=False)
        self.assertNotEqual(0, wrong.returncode)

    def test_private_material_stays_out_of_core_mount(self):
        self.assertTrue((self.state / 'pki' / 'ca.key').is_file())
        self.assertFalse(any(path.suffix == '.key' for path in self.tls.iterdir()))
        self.assertEqual(0o400, os.stat(self.tls / 'core.p12').st_mode & 0o777)

    def test_rerun_preserves_material(self):
        before = (self.tls / 'core.p12').read_bytes()
        pki.generate(self.state, owner=None)
        self.assertEqual(before, (self.tls / 'core.p12').read_bytes())


class HostHelperTests(unittest.TestCase):
    def test_certs_creates_password_on_host_and_runs_isolated_container(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)
            (state / 'config.json').write_text(json.dumps({'project': 'vault-lab5-123456ab', 'mode': 'http'}))
            with patch.object(lab, 'STATE', state), patch.object(lab, 'execute') as run:
                lab.certificates()
            self.assertTrue((state / 'tls' / 'keystore-password.txt').read_text())
            command = run.call_args.args[0]
            self.assertIn('pki', command)
            self.assertNotIn('--force', command)


if __name__ == '__main__':
    unittest.main()
