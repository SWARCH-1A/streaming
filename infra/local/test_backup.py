import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('local_backup', Path(__file__).with_name('backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupRecovery(unittest.TestCase):
    def test_lan_backup_starts_recovery_on_loopback_without_inheriting_public_host(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / 'config'
            config.mkdir()
            (config / 'Caddyfile').write_text('fixture caddy')
            for name in ('ca.crt', 'ca.key', 'chat-redis.key'):
                (config / name).write_text('fixture ' + name)
            (config / 'environment.env').write_text('LOCAL_HTTPS_BIND=0.0.0.0\nLOCAL_PUBLIC_HOST=vault.local\n'
                                                    'LOCAL_LAN_IP=192.168.0.7\nWEB_ORIGIN=https://vault.local:3445\n')
            prefix = 'streaming-restore-fixture'
            (root / (prefix + '.compose.json')).write_text('{}')
            manifest = {'images': {'proxy': 'sha256:fixture', 'audit': 'sha256:audit-fixture'},
                        'config': [{'file': name} for name in ('Caddyfile', 'environment.env', 'ca.crt', 'ca.key', 'chat-redis.key')]}
            sockets = [Mock() for _ in range(3)]
            for index, sock in enumerate(sockets):
                sock.getsockname.return_value = ('127.0.0.1', 45000 + index)
            with patch.object(backup, 'docker', side_effect=['network-id', '[]', '']) as docker, \
                    patch.object(backup.socket, 'socket', side_effect=sockets), patch('builtins.print'):
                backup.start_restored(root, prefix, manifest)
            env = dict(line.split('=', 1) for line in (root / (prefix + '-state/environment.env')).read_text().splitlines())
            self.assertEqual(env['LOCAL_HTTPS_BIND'], '127.0.0.1')
            self.assertEqual(env['LOCAL_PUBLIC_HOST'], 'localhost')
            self.assertEqual(env['LOCAL_LAN_IP'], '127.0.0.1')
            self.assertTrue(env['WEB_ORIGIN'].startswith('https://localhost:'))
            self.assertEqual(docker.call_args.args[:2], ('compose', '--env-file'))
            self.assertIn(str(backup.ROOT / 'infra/audit/compose.yaml'), docker.call_args.args)
            # A backup made before ADR-029 gets its own Chat Redis credential; the ACL holds only the hash.
            password = env['CHAT_REDIS_PASSWORD']
            acl = (root / (prefix + '-state/chat-redis.acl')).read_text()
            self.assertGreaterEqual(len(password), 32)
            self.assertNotIn(password, acl)
            self.assertIn('#' + hashlib.sha256(password.encode()).hexdigest(), acl)
            self.assertIn('user default off', acl)
            # A backup made before ADR-031 gets the current Caddyfile, which trusts only the restored WAF address.
            # The edge zone (WAF and Caddy) gets its own free subnet, separate from the API zone.
            self.assertEqual((env['LOCAL_SUBNET'], env['LOCAL_EDGE_SUBNET']), ('192.168.240.0/24', '192.168.241.0/24'))
            self.assertEqual((env['LOCAL_WAF_IP'], env['LOCAL_EDGE_PROXY_IP']), ('192.168.241.2', '192.168.241.3'))
            caddyfile = (root / (prefix + '-state/Caddyfile')).read_text()
            self.assertIn('trusted_proxies static {$LOCAL_WAF_IP}', caddyfile)
            self.assertIn('header_up X-Forwarded-For {client_ip}', caddyfile)
            # Mounted files keep the installation modes; secrets that are never mounted stay private.
            if os.name == 'posix':
                mode = lambda name: (root / (prefix + '-state') / name).stat().st_mode & 0o777
                self.assertEqual({name: mode(name) for name in ('ca.crt', 'chat-redis.key', 'chat-redis.acl', 'ca.key', 'environment.env')},
                                 {'ca.crt': 0o644, 'chat-redis.key': 0o644, 'chat-redis.acl': 0o644, 'ca.key': 0o600, 'environment.env': 0o600})

    def test_restore_keeps_an_existing_chat_redis_password(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            env = {'CHAT_REDIS_PASSWORD': 'p' * 48}
            backup.ensure_chat_redis_credential(state, env)
            self.assertEqual(env['CHAT_REDIS_PASSWORD'], 'p' * 48)
            acl = (state / 'chat-redis.acl').read_text()
            self.assertIn('#' + hashlib.sha256(b'p' * 48).hexdigest(), acl)
            for denied in ('-@dangerous', '-script|flush', '-function', 'resetchannels', '~chat:*'):
                self.assertIn(denied, acl)

    def test_restore_keeps_a_caddyfile_that_already_trusts_the_waf(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / 'Caddyfile').write_text('servers {\n  trusted_proxies static {$LOCAL_WAF_IP}\n}\n')
            backup.ensure_waf_trust(state)
            self.assertEqual((state / 'Caddyfile').read_text(), 'servers {\n  trusted_proxies static {$LOCAL_WAF_IP}\n}\n')

    def source(self, root):
        path = root / 'volume-00.tar'
        path.write_bytes(b'fixture archive')
        entry = {'name': 'streaming-local_fixture', 'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        (root / 'manifest.json').write_text(json.dumps({'version': 1, 'project': 'streaming-local', 'volumes': [entry], 'config': []}))
        return path

    def test_corrupt_archive_is_rejected_before_any_docker_operation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, 'docker') as docker:
            root = Path(directory)
            path = self.source(root)
            path.write_bytes(b'corrupt')
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                backup.restore(root, 'streaming-restore-fixture')
            docker.assert_not_called()

    def test_existing_recovery_volume_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, 'docker') as docker, patch.object(backup.subprocess, 'run', return_value=Mock(returncode=0)):
            root = Path(directory)
            self.source(root)
            with self.assertRaisesRegex(RuntimeError, 'overwrite'):
                backup.restore(root, 'streaming-restore-fixture')
            docker.assert_not_called()

    def test_partial_stop_failure_still_restarts_only_original_running_containers(self):
        containers = [{'Id': 'owned-running', 'State': {'Running': True}}, {'Id': 'owned-stopped', 'State': {'Running': False}}]
        for item in containers:
            item.update(Config={'Labels': {'com.docker.compose.service': item['Id']}}, Image='sha256:fixture')
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, 'inventory', return_value=(containers, [])), patch.object(backup, 'docker', side_effect=[RuntimeError('stop failed'), '']) as docker:
            with self.assertRaisesRegex(RuntimeError, 'stop failed'):
                backup.backup(Path(directory) / 'new')
            self.assertEqual(('start', 'owned-running'), docker.call_args_list[-1].args)
            self.assertFalse((Path(directory) / 'new/manifest.json').exists())


if __name__ == '__main__':
    unittest.main()
