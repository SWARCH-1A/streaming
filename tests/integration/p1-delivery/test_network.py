"""Exercise destination fencing, aggregate throughput, isolation and teardown.

Optional --tls-origin/--ca additionally checks the real P1 certificate through a
tunnel with trusted and untrusted roots. Neither branch disables TLS validation.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import socketserver
import ssl
import threading
import time
import unittest
from urllib.parse import urlsplit

from network import PlayerNetwork

PAYLOAD = b"x" * 262144


def connect(port, authority):
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
    sock.sendall(f"CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n\r\n".encode())
    header = bytearray()
    while not header.endswith(b"\r\n\r\n"):
        data = sock.recv(1)
        if not data:
            break
        header.extend(data)
    return sock, bytes(header)


class DataHandler(socketserver.BaseRequestHandler):
    def handle(self):
        if self.request.recv(1):
            self.request.sendall(PAYLOAD)


class NetworkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), DataHandler)
        cls.server.daemon_threads = True
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.origin = f"https://localhost:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join(timeout=2)

    def transfer(self, network, player):
        sock, header = connect(network.ports[player], network.authority)
        with sock:
            self.assertIn(b"200 Connection Established", header)
            sock.sendall(b"x")
            body = bytearray()
            while chunk := sock.recv(65536):
                body.extend(chunk)
            self.assertEqual(bytes(body), PAYLOAD)

    def test_parallel_connections_share_one_player_allowance(self):
        with PlayerNetwork(self.origin, 1, down_mbps=1, setup_delay_ms=0) as network:
            network.begin(time.monotonic(), time.monotonic() + 10)
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(lambda _: self.transfer(network, 0), range(2)))
            elapsed = time.monotonic() - started
            self.assertGreaterEqual(elapsed, len(PAYLOAD) * 2 * 8 / 1_000_000 * .95)
            self.assertLess(elapsed, 8)
            snapshot = network.snapshot()
            self.assertEqual(snapshot["players"][0]["measuredDownTlsBytes"], len(PAYLOAD) * 2)

    def test_players_have_independent_allowances(self):
        with PlayerNetwork(self.origin, 2, down_mbps=1, setup_delay_ms=0) as network:
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(lambda i: self.transfer(network, i), range(2)))
            elapsed = time.monotonic() - started
            self.assertGreaterEqual(elapsed, len(PAYLOAD) * 8 / 1_000_000 * .95)
            self.assertLess(elapsed, 3.5)

    def test_rejects_arbitrary_destinations_and_methods(self):
        with PlayerNetwork(self.origin, 1) as network:
            for authority in ("example.test:443", "127.0.0.1:443", "localhost:1", network.authority + "@example.test:443"):
                sock, header = connect(network.ports[0], authority)
                with sock:
                    self.assertIn(b"403 Forbidden", header)
            with socket.create_connection(("127.0.0.1", network.ports[0]), timeout=5) as sock:
                sock.sendall(b"GET https://localhost/ HTTP/1.1\r\n\r\n")
                self.assertIn(b"403 Forbidden", sock.recv(1024))
            self.assertEqual(network.snapshot()["players"][0]["connections"], 0)

    def test_large_header_and_shutdown_are_bounded(self):
        with PlayerNetwork(self.origin, 1) as network:
            port = network.ports[0]
            with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
                sock.sendall(b"x" * 10000)
                self.assertEqual(sock.recv(1024), b"")
            # An idle accepted tunnel must also close when the fixture exits.
            idle, header = connect(port, network.authority)
            self.assertIn(b"200 Connection Established", header)
        with idle:
            self.assertEqual(idle.recv(1024), b"")
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", port), timeout=1)
        self.assertFalse(network.thread.is_alive())
        self.assertFalse(network.connections)

    def test_only_own_loopback_origin_is_allowed(self):
        for origin in ("http://localhost:3443", "https://example.test:3443", "https://localhost:3443/path", "https://user@localhost:3443"):
            with self.assertRaises(ValueError):
                PlayerNetwork(origin, 1)


def verify_tls(origin, ca):
    hostname = urlsplit(origin).hostname
    with PlayerNetwork(origin, 1) as network:
        sock, header = connect(network.ports[0], network.authority)
        assert b"200 Connection Established" in header
        context = ssl.create_default_context(cafile=str(ca))
        with context.wrap_socket(sock, server_hostname=hostname) as tls:
            tls.sendall(f"GET /api/taxonomy HTTP/1.1\r\nHost: {network.authority}\r\nConnection: close\r\n\r\n".encode())
            assert b"200" in tls.recv(4096).split(b"\r\n")[0]
        sock, _ = connect(network.ports[0], network.authority)
        try:
            with ssl.create_default_context().wrap_socket(sock, server_hostname=hostname):
                raise AssertionError("Untrusted fixture certificate was accepted")
        except ssl.SSLCertVerificationError:
            sock.close()
        sock, _ = connect(network.ports[0], network.authority)
        try:
            with context.wrap_socket(sock, server_hostname="wrong.example.test"):
                raise AssertionError("Wrong TLS server name was accepted")
        except ssl.SSLError:
            # Caddy may reject unknown SNI before sending a certificate; otherwise
            # the client's enabled hostname validation rejects the wrong SAN.
            sock.close()
        assert network.snapshot()["players"][0]["downTlsBytes"] > 0
    print("PASS: real P1 TLS through opaque tunnel; untrusted CA and wrong TLS name rejected", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tls-origin")
    parser.add_argument("--ca", type=Path)
    args = parser.parse_args()
    if bool(args.tls_origin) != bool(args.ca):
        parser.error("--tls-origin and --ca are required together")
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(NetworkTests))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.tls_origin:
        verify_tls(args.tls_origin, args.ca)
