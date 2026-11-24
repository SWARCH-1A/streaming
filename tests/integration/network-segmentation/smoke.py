#!/usr/bin/env python3
"""Start the actual TLS deployment with fresh state and smoke-test APIs behind segmentation."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import runpy
import socket
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true', help='Only when local runtime images match this checkout')
    args = parser.parse_args()
    project = 'segmentation-app-' + uuid.uuid4().hex[:10]
    os.environ['P1_PROFILE'] = 'local'
    spec = importlib.util.spec_from_file_location('manage', ROOT / 'infra/p1/manage.py')
    manage = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(manage)
    with tempfile.TemporaryDirectory(prefix=project) as folder:
        # Canonical path required for Docker Desktop bind mounts on macOS.
        folder = Path(folder).resolve()
        manage.HERE, manage.STATE, manage.PROJECT = folder, folder / 'state', project
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            manage.HTTPS_PORT = str(listener.getsockname()[1])
        env = manage.initialize()
        for relative in ('infra/broker/bootstrap.py', 'infra/local/recovery_config.py',
                         'infra/local/notifications_config.py', 'infra/local/vod_config.py',
                         'infra/local/chat_config.py', 'infra/local/audit_config.py'):
            env = runpy.run_path(str(ROOT / relative))['initialize'](env, manage.STATE, manage.run)

        command = ['docker', 'compose', '--project-directory', str(ROOT),
                   '--env-file', str(manage.STATE / 'environment.env'), '-p', project]
        for relative in ('compose.yaml', 'infra/broker/compose.yaml', 'infra/audit/compose.yaml'):
            command += ['-f', str(ROOT / relative)]
        model = json.loads(manage.run([*command, 'config', '--format', 'json'], env=env))
        for name, service in model['services'].items():
            for port in service.get('ports', []):
                port['host_ip'] = '127.0.0.1'
                if name == 'waf':
                    port['published'] = manage.HTTPS_PORT
                else:
                    port.pop('published', None)
        path = folder / 'compose.json'
        path.write_text(json.dumps(model))
        os.chmod(path, 0o600)
        command = ['docker', 'compose', '-p', project, '-f', str(path)]
        try:
            if not args.skip_build:
                print('Building deployment images from current checkout', flush=True)
                manage.run([*command, 'build', '--quiet'], env=env, timeout=1800)
            print('Starting real TLS services on isolated volumes', flush=True)
            manage.run([*command, 'up', '-d', '--wait', '--wait-timeout', '240'], env=env, timeout=360)
            manage.wait_local(env)
            context = ssl.create_default_context(cafile=str(manage.STATE / 'ca.crt'))
            http = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                              urllib.request.HTTPSHandler(context=context))

            def call(path, status=200, body=None, token=None):
                headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
                if token:
                    headers['Authorization'] = 'Bearer ' + token
                request = urllib.request.Request(env['WEB_ORIGIN'] + path, headers=headers,
                                                 data=None if body is None else json.dumps(body).encode())
                try:
                    response = http.open(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    assert response.status == status, f'{path}: {response.status}, expected {status}'
                    return json.load(response)

            password = 'Fixture-' + uuid.uuid4().hex + '!aA3'
            handle = 'net_' + uuid.uuid4().hex[:12]
            call('/api/identity/registrations', 201, {'handle': handle, 'email': handle + '@example.test',
                                                    'password': password})
            session = call('/api/identity/mobile-sessions', 200, {'login': handle, 'password': password})
            token = session['accessToken']
            call('/api/admin/audit-events', 401)
            call('/api/admin/audit-events', 403, token=token)
            # Explicit fixture provisioning; no production accounts or volumes exist here.
            manage.run([*command, 'exec', '-T', 'core-db', 'psql', '-U', 'core', '-d', 'core',
                        '-c', "UPDATE identity.accounts SET account_role='ADMIN' WHERE handle='" + handle + "'"], env=env)
            page = call('/api/admin/audit-events', token=token)
            assert 'items' in page
            print(json.dumps({'result': 'PASS', 'checks': ['all-service readiness', 'HTTPS taxonomy',
                  'SSR', 'registration/login', 'audit 401/403', 'Core-to-Auditor TLS query']}), flush=True)
        finally:
            # Only this random project's resources; the normal installation remains untouched.
            manage.run([*command, 'down', '--volumes', '--remove-orphans'], env=env, timeout=120)


if __name__ == '__main__':
    main()
