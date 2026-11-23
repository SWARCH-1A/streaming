#!/usr/bin/env python3
"""Disposable real WAF/Web/Core/VOD/Auditor fixture for the administrative audit UI."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import runpy
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true', help='Only when all local images match this checkout')
    parser.add_argument('--hold', action='store_true', help='Keep the private browser fixture ready until its release file exists (at most 30 min)')
    args = parser.parse_args()
    os.environ['P1_PROFILE'] = 'local'
    for key in ('P1_HTTPS_PORT', 'P1_RTMPS_PORT', 'LOCAL_SUBNET', 'LOCAL_PROXY_IP', 'LOCAL_GATEWAY_IP',
                'LOCAL_EDGE_SUBNET', 'LOCAL_WAF_IP', 'LOCAL_EDGE_PROXY_IP'):
        os.environ.pop(key, None)
    manager = load('audit_ui_manager', 'infra/p1/manage.py')
    api = load('audit_ui_api', 'tests/integration/audit/run.py')
    project = 'audit-ui-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=project) as folder:
        folder = Path(folder).resolve()
        manager.HERE, manager.STATE, manager.PROJECT = folder, folder / 'state', project
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            manager.HTTPS_PORT = str(listener.getsockname()[1])
        env = manager.initialize()
        for relative in ('infra/broker/bootstrap.py', 'infra/local/recovery_config.py',
                         'infra/local/notifications_config.py', 'infra/local/vod_config.py',
                         'infra/local/chat_config.py', 'infra/local/audit_config.py'):
            env = runpy.run_path(str(ROOT / relative))['initialize'](env, manager.STATE, manager.run)
        config = json.loads(manager.command(env, 'config', '--format', 'json'))
        for name, service in config['services'].items():
            for port in service.get('ports', []):
                port['host_ip'] = '127.0.0.1'
                if name == 'waf':
                    port['published'] = manager.HTTPS_PORT
                else:
                    port.pop('published', None)
        manifest = folder / 'compose.json'
        manifest.write_text(json.dumps(config))
        manifest.chmod(0o600)
        command = ['docker', 'compose', '-p', project, '-f', str(manifest)]

        def compose(*arguments, timeout=180):
            return manager.run([*command, *arguments], env=env, cwd=ROOT, timeout=timeout)

        try:
            if not args.skip_build:
                print('Building current deployment images', flush=True)
                compose('build', '--quiet', timeout=1800)
            print('Starting isolated deployment with WAF and TLS', flush=True)
            compose('up', '-d', '--wait', '--wait-timeout', '240', timeout=360)
            manager.wait_local(env)
            context = ssl.create_default_context(cafile=str(manager.STATE / 'ca.crt'))
            api.HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
            origin = env['WEB_ORIGIN']
            password = 'Audit fixture ' + secrets.token_urlsafe(24) + '!aA3'
            users = {}
            for handle in ('audit_operator', 'audit_creator', 'audit_viewer'):
                account = api.call(origin, '/api/identity/registrations', 'POST',
                                   {'handle': handle, 'email': handle + '@example.test', 'password': password},
                                   headers={'Idempotency-Key': str(uuid.uuid4())}, expected=201)
                session = api.call(origin, '/api/identity/mobile-sessions', 'POST', {'login': handle, 'password': password})
                users[handle] = {**account, 'token': session['accessToken']}
            compose('exec', '-T', 'core-db', 'psql', '-U', 'core', '-d', 'core', '-c',
                    "UPDATE identity.accounts SET account_role='ADMIN' WHERE handle='audit_operator'")
            admin, owner, viewer = (users[name] for name in ('audit_operator', 'audit_creator', 'audit_viewer'))
            api.call(origin, '/api/admin/audit-events', expected=401)
            api.call(origin, '/api/admin/audit-events', token=viewer['token'], expected=403)
            category = api.call(origin, '/api/taxonomy')['categories'][0]['id']
            initial = {'sessionId': 'session_audit_ui', 'streamId': 'stream_audit_ui', 'channelId': owner['channelId'],
                       'streamGeneration': 1, 'title': 'Sesión de prueba de auditoría', 'description': '',
                       'categoryId': category, 'tagIds': [], 'autopublish': False, 'preferenceVersion': 0}
            capture_env = folder / 'capture.env'
            capture_env.write_text('CAPTURE_TOKEN=' + env['VOD_CAPTURE_SERVICE_TOKEN'] + '\n')
            capture_env.chmod(0o600)
            capture = '''import json, os, ssl, sys, urllib.request, uuid
body = sys.stdin.read().encode()
request = urllib.request.Request('https://vod:8099/internal/vod/recordings/session_audit_ui', data=body, method='PUT',
    headers={'Content-Type':'application/json', 'X-Service-Name':'media-capture',
    'X-Service-Token':os.environ['CAPTURE_TOKEN'], 'Idempotency-Key':str(uuid.uuid4())})
context = ssl.create_default_context(cafile='/ca.crt')
# Match the existing local-CA fixtures: retain chain and hostname verification;
# Python 3.13 additionally requires extensions absent from the academic CA.
context.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)
with urllib.request.urlopen(request, context=context, timeout=10) as response:
    print(response.read().decode())
'''
            result = subprocess.run(['docker', 'run', '--rm', '-i', '--read-only', '--cap-drop', 'ALL',
                                     '--network', project + '_default', '--env-file', str(capture_env),
                                     '-v', str(manager.STATE / 'ca.crt') + ':/ca.crt:ro',
                                     'python:3.13-alpine', 'python', '-c', capture],
                                    input=json.dumps(initial), capture_output=True, text=True, timeout=60)
            if result.returncode:
                raise RuntimeError('Private capture fixture failed: ' + result.stderr[-1000:])
            recording = json.loads(result.stdout)
            for _ in range(5):
                api.call(origin, '/api/vods/' + recording['vodId'], 'PATCH',
                         {'expectedVersion': recording['version'], 'title': 'Cambio sin permiso'},
                         token=viewer['token'], headers={'Idempotency-Key': str(uuid.uuid4())}, expected=403)
            api.call(origin, '/api/vods/' + recording['vodId'], 'PATCH',
                     {'expectedVersion': recording['version'], 'title': 'Grabación revisada por su propietario'},
                     token=owner['token'], headers={'Idempotency-Key': str(uuid.uuid4())})
            for _ in range(52):
                api.call(origin, '/api/admin/users?limit=1', token=admin['token'])

            def pages_ready():
                page = api.call(origin, '/api/admin/audit-events?limit=50', token=admin['token'])
                return page if len(page['items']) == 50 and page['nextCursor'] else None

            first = api.wait(pages_ready, 'Audit events and second page', 90)
            second = api.call(origin, '/api/admin/audit-events?limit=50&cursor=' + first['nextCursor'], token=admin['token'])
            assert second['items'] and not ({item['position'] for item in first['items']} & {item['position'] for item in second['items']})
            filtered = api.call(origin, '/api/admin/audit-events?producer=vod&outcome=DENIED&targetId=' + recording['vodId'], token=admin['token'])
            assert len(filtered['items']) == 5
            request_id = filtered['items'][0]['event']['correlationId']
            correlated = api.call(origin, '/api/admin/audit-events?requestId=' + request_id, token=admin['token'])
            assert len(correlated['items']) == 1
            assert all(item['event']['payload']['outcome'] == 'DENIED' for item in filtered['items'])
            print('PASS: WAF permits paginated and filtered queries; 401/403, Core/VOD and correlation verified', flush=True)
            if args.hold:
                browser = folder / 'browser.json'
                browser.write_text(json.dumps({'origin': origin, 'password': password, 'admin': 'audit_operator',
                                              'viewer': 'audit_viewer', 'vodId': recording['vodId'], 'requestId': request_id,
                                              'compose': str(manifest), 'project': project}))
                browser.chmod(0o600)
                print('Browser fixture: ' + str(browser), flush=True)
                print('Release file: ' + str(folder / 'release'), flush=True)
                deadline = time.monotonic() + 1800
                while not (folder / 'release').exists() and time.monotonic() < deadline:
                    time.sleep(.5)
        finally:
            compose('down', '--volumes', '--remove-orphans', timeout=120)


if __name__ == '__main__':
    main()
