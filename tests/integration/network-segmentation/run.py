#!/usr/bin/env python3
"""Verify the real Compose network graph using disposable TCP fixtures, without installation data."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import ipaddress
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('network_policy', ROOT / 'infra/local/network_policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
PORTS = {
    **{name: [6379 if name == 'chat-db' else 5432] for name in policy.STORES if name != 'minio'},
    'minio': [9000], 'core': [8081, 8082], 'chat': [8085, 8086],
    'live': [8080, 8091, 8090, 8888], 'vod': [8098, 8099],
    'notifications': [8095], 'audit': [8096], 'gateway': [8097], 'web': [3443],
    'proxy': [3443], 'waf': [8443], 'media-server': [1936, 9997, 8888],
    'media-recorder': [8890], 'mailpit': [1025, 8025], 'broker': [5671, 15671],
    'minio-init': [], 'broker-ops': [],
}
SERVER = '''import json, socket, sys, threading, time
def serve(port):
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('0.0.0.0', port)); s.listen(128)
    while True:
        c, _ = s.accept(); c.close()
for port in json.loads(sys.argv[1]):
    threading.Thread(target=serve, args=(port,), daemon=True).start()
while True: time.sleep(3600)
'''
PROBE = '''import concurrent.futures, json, socket, sys
def check(item):
    host, port, expected, label = item
    try:
        with socket.create_connection((host, port), timeout=.6): pass
        reachable = True
    except OSError: reachable = False
    return (label, reachable, expected)
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
    print(json.dumps(list(pool.map(check, json.loads(sys.argv[1])))))
'''


def run(*args):
    p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(f'{args[0]} failed: {p.stderr[-2500:]}')
    return p.stdout


def unused_subnet(exclude=()):
    ids = run('docker', 'network', 'ls', '-q').split()
    existing = json.loads(run('docker', 'network', 'inspect', *ids)) if ids else []
    occupied = [ipaddress.ip_network(c['Subnet']) for n in existing
                for c in (n.get('IPAM', {}).get('Config') or []) if c.get('Subnet')]
    for octet in range(240, 255):
        candidate = ipaddress.ip_network(f'192.168.{octet}.0/24')
        if str(candidate) not in exclude and not any(candidate.overlaps(n) for n in occupied if n.version == 4):
            return str(candidate)
    raise RuntimeError('No unused fixture subnet available')


def dependencies():
    edges = []
    for target, port in [('core', 8081), ('chat', 8085), ('live', 8080), ('live', 8888),
                         ('vod', 8098), ('notifications', 8095), ('gateway', 8097), ('web', 3443)]:
        edges.append(('proxy', target, port))
    for source in ('gateway', 'web'):
        edges += [(source, target, port) for target, port in [('core', 8081), ('live', 8080), ('vod', 8098)]]
    edges.append(('web', 'gateway', 8097))
    edges.append(('waf', 'proxy', 3443))
    edges += [(source, 'core', 8082) for source in ('chat', 'live', 'vod', 'notifications')]
    edges += [('core', 'live', 8091), ('chat', 'vod', 8099), ('vod', 'media-recorder', 8890),
              ('media-recorder', 'vod', 8099), ('media-recorder', 'live', 8091),
              ('media-recorder', 'live', 8888), ('live', 'media-server', 9997),
              ('live', 'media-server', 8888), ('media-server', 'live', 8090),
              ('notifications', 'mailpit', 1025), ('core', 'audit', 8096)]
    edges += [(source, 'broker', 5671) for source in policy.GROUPS['messaging'] - {'broker'} | {'audit'}]
    return edges


def exercise(model, ids):
    inspection = json.loads(run('docker', 'inspect', *ids.values()))
    addresses = {c['Config']['Labels']['com.docker.compose.service']:
                 [n['IPAddress'] for n in c['NetworkSettings']['Networks'].values()]
                 for c in inspection}
    cases = {name: [] for name in model['services']}

    def add(source, target, port, expected):
        # DNS hiding alone is insufficient: probe every interface by literal IP too.
        for host in [target, *addresses[target]]:
            cases[source].append([host, port, expected, f'{source}->{target}:{port} ({host})'])

    for store, (_, owners) in policy.STORES.items():
        for source in cases.keys() - {store}:
            add(source, store, PORTS[store][0], source in owners)
    for source, target, port in dependencies():
        # Multi-homed targets may have unreachable private interfaces. DNS must resolve
        # through a shared network; positive literal-IP probes use only shared interfaces.
        shared = policy.networks(model['services'][source]) & policy.networks(model['services'][target])
        assert shared, f'No shared network for required dependency {source}->{target}'
        cases[source].append([target, port, True, f'{source}->{target}:{port} (DNS)'])
        target_info = next(c for c in inspection if c['Config']['Labels']['com.docker.compose.service'] == target)
        for name in shared:
            ip = target_info['NetworkSettings']['Networks'][model['networks'][name]['name']]['IPAddress']
            cases[source].append([ip, port, True, f'{source}->{target}:{port} ({name})'])
    for source in ('gateway', 'web', 'proxy'):
        add(source, 'broker', 5671, False)
    for source in ('gateway', 'web', 'proxy', 'chat', 'live', 'vod', 'notifications', 'media-recorder', 'waf'):
        add(source, 'audit', 8096, False)
    # ADR-031: the public entry reaches only Caddy, never a service or the broker directly.
    # Published ports (RTMPS 1936) are public by design and stay reachable by IP from any bridge.
    for target in ('core', 'chat', 'live', 'vod', 'notifications', 'gateway', 'web', 'media-server', 'broker'):
        port = next(p for p in PORTS[target] if p not in policy.PUBLIC.get(target, ()))
        add('waf', target, port, False)

    def probe(source):
        return json.loads(run('docker', 'exec', ids[source], 'python', '-c', PROBE, json.dumps(cases[source])))

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = [item for group in pool.map(probe, cases) for item in group]
    failures = [label + f': reachable={actual}, expected={expected}'
                for label, actual, expected in results if actual != expected]
    assert not failures, '\n'.join(failures)
    return {'allowed': sum(expected for _, _, expected in results),
            'blocked': sum(not expected for _, _, expected in results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--static-only', action='store_true', help='Validate merged manifests without a Docker daemon')
    parser.add_argument('--upgrade', action='store_true', help='First start the old single-network graph, then apply segmentation')
    args = parser.parse_args()
    if args.static_only and args.upgrade:
        parser.error('--upgrade requires Docker')
    project = 'segmentation-test-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=project) as folder:
        subnet = '192.168.254.0/24' if args.static_only else unused_subnet()
        model = policy.configuration(folder, project=project, subnet=subnet,
                                     edge='192.168.253.0/24' if args.static_only else unused_subnet((subnet,)))
        policy.validate(model)
        if args.static_only:
            print('PASS: merged Compose network policy')
            return
        # Copy the actual attachment/IPAM graph, replace processes with TCP listeners.
        # No installation volumes, credentials, ports or configuration files are reused.
        fixture = {'services': {}, 'networks': model['networks']}
        for name, service in model['services'].items():
            fixture['services'][name] = {
                'image': 'python:3.13-alpine', 'networks': service['networks'],
                'command': ['python', '-c', SERVER, json.dumps(PORTS[name])],
                'read_only': True, 'cap_drop': ['ALL'],
                'security_opt': ['no-new-privileges:true'],
            }
            if name in policy.PUBLIC:
                fixture['services'][name]['ports'] = [
                    {'host_ip': '127.0.0.1', 'target': port} for port in policy.PUBLIC[name]]
        path = Path(folder) / 'compose.json'
        path.write_text(json.dumps(fixture))
        command = ['docker', 'compose', '-p', project, '-f', str(path)]
        try:
            if args.upgrade:
                legacy = json.loads(json.dumps(fixture))
                legacy['networks'] = {'default': legacy['networks']['default']}
                legacy['networks']['default'].pop('driver', None)
                for name, service in legacy['services'].items():
                    service['networks'] = {'default': service['networks'].get('default', {})}
                legacy_path = Path(folder) / 'legacy.json'
                legacy_path.write_text(json.dumps(legacy))
                run('docker', 'compose', '-p', project, '-f', str(legacy_path), 'up', '-d', '--quiet-pull')
            run(*command, 'up', '-d', '--quiet-pull')
            containers = [
                json.loads(line) for line in run(*command, 'ps', '--format', 'json').splitlines()]
            ids = {c['Service']: c['ID'] for c in containers}
            assert set(ids) == set(model['services']), 'Missing fixture containers'
            # Wait for every listener before testing denials, avoiding false positives.
            for name, ports in PORTS.items():
                checks = [['127.0.0.1', port, True, name] for port in ports]
                deadline = time.monotonic() + 20
                while checks:
                    results = json.loads(run('docker', 'exec', ids[name], 'python', '-c', PROBE, json.dumps(checks)))
                    if all(actual for _, actual, _ in results):
                        break
                    assert time.monotonic() < deadline, f'Listener unavailable: {name}'
                    time.sleep(.1)
            result = exercise(model, ids)
            for name, ports in policy.PUBLIC.items():
                for port in ports:
                    address = run(*command, 'port', name, str(port)).strip()
                    with socket.create_connection(('127.0.0.1', int(address.rsplit(':', 1)[1])), timeout=3):
                        pass
            print(json.dumps({'result': 'PASS', 'networks': len(model['networks']),
                              'services': len(ids), 'tcp': result, 'published_ports': 3,
                              'upgraded_single_network': args.upgrade}))
        finally:
            run(*command, 'down', '--volumes', '--remove-orphans')


if __name__ == '__main__':
    main()
