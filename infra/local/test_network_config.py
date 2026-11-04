"""Exercise real LAN certificates and configuration preservation without Docker or system trust changes."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("network_config", Path(__file__).with_name("network_config.py"))
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)


def run(args):
    return subprocess.check_output(args, stderr=subprocess.DEVNULL, text=True)


class NetworkConfiguration(unittest.TestCase):
    def test_rejects_nonlocal_hosts_and_caddy_or_env_injection(self):
        for value in ("example.com", "https://vault.local", "vault.local:3445", "vault.local\nSECRET=value",
                      "vault.local{", "-vault.local", "127.0.0.1", "0.0.0.0", "8.8.8.8", "::1"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                network.public_host(value)
        for value in ("0.0.0.0", "127.0.0.1", "169.254.0.1", "8.8.8.8", "::1"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                network.private_address(value)
        with self.assertRaises(RuntimeError):
            network.validate({"LOCAL_HTTPS_BIND": "0.0.0.0"})

    def test_real_leaf_certificate_preserves_ca_secrets_and_loopback_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(state / "ca.key"),
                 "-out", str(state / "ca.crt"), "-days", "1", "-subj", "/CN=LAN test CA"])
            run(["openssl", "genrsa", "-out", str(state / "web.key"), "2048"])
            preserved = {name: (state / name).read_bytes() for name in ("ca.crt", "ca.key", "web.key")}
            env = {"P1_HTTPS_PORT": "3445", "WEB_ORIGIN": "https://localhost:3445",
                   "LOCAL_SUBNET": "192.168.240.0/24", "CORE_DB_PASSWORD": "fixture-preserved"}
            path = state / "environment.env"
            path.write_text("".join(f"{key}={value}\n" for key, value in env.items()))
            updated = network.configure(env, state, run, host="Vault-Mac.local", address="192.168.0.7")
            self.assertEqual(updated["WEB_ORIGIN"], "https://vault-mac.local:3445")
            self.assertEqual(updated["LOCAL_HTTPS_BIND"], "0.0.0.0")
            self.assertEqual(updated["CORE_DB_PASSWORD"], env["CORE_DB_PASSWORD"])
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            cert = run(["openssl", "x509", "-in", str(state / "web.crt"), "-noout", "-text"])
            for name in ("DNS:localhost", "DNS:web", "DNS:proxy", "DNS:vault-mac.local", "IP Address:192.168.0.7"):
                self.assertIn(name, cert)
            for host in ("vault-mac.local", "localhost", "web"):
                run(["openssl", "verify", "-CAfile", str(state / "ca.crt"), "-verify_hostname", host, str(state / "web.crt")])
            run(["openssl", "verify", "-CAfile", str(state / "ca.crt"), "-verify_ip", "192.168.0.7", str(state / "web.crt")])
            restored = network.configure(updated, state, run, loopback=True)
            self.assertEqual(restored["WEB_ORIGIN"], "https://localhost:3445")
            self.assertEqual(restored["LOCAL_HTTPS_BIND"], "127.0.0.1")
            for name, contents in preserved.items():
                self.assertEqual((state / name).read_bytes(), contents)

    def test_lan_overlap_is_rejected_before_reading_or_changing_files(self):
        with self.assertRaisesRegex(RuntimeError, "overlaps"):
            network.configure({"LOCAL_SUBNET": "192.168.0.0/24"}, Path("/nonexistent"), run,
                              host="vault.local", address="192.168.0.7")


if __name__ == "__main__":
    unittest.main()
