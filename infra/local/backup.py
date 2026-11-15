#!/usr/bin/env python3
"""Quiescent local P2 backups and non-destructive restoration to fresh Docker volumes.

Archives contain database files and installation secrets. Keep the directory private.
No Docker socket is mounted, and no existing volume is overwritten or deleted.
"""
from __future__ import annotations
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import socket
import shutil
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / 'infra/p1/.state-local'
PROJECT = 'streaming-local'
HELPER = 'alpine:3.23'


def docker(*args, timeout=180):
    result = subprocess.run(['docker', *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # Docker errors may include environment values: do not echo raw diagnostics.
        raise RuntimeError('Docker operation failed: ' + args[0])
    return result.stdout


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def private_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
    path.chmod(0o600)


def inventory():
    ids = docker('ps', '-aq', '--filter', f'label=com.docker.compose.project={PROJECT}').split()
    if not ids:
        raise RuntimeError('Initialize the local installation before backing it up')
    containers = json.loads(docker('inspect', *ids))
    volumes = {}
    for container in containers:
        for mount in container['Mounts']:
            if mount['Type'] != 'volume':
                continue
            name = mount['Name']
            info = json.loads(docker('volume', 'inspect', name))[0]
            if (info.get('Labels') or {}).get('com.docker.compose.project') != PROJECT:
                raise RuntimeError('Refusing a volume not owned by this installation')
            volumes[name] = {'name': name, 'logicalName': info['Labels']['com.docker.compose.volume'], 'image': container['Image'], 'destination': mount['Destination']}
    if not volumes:
        raise RuntimeError('No persistent volumes found')
    return containers, sorted(volumes.values(), key=lambda volume: volume['name'])


def backup(target):
    target = target.resolve()
    target.mkdir(mode=0o700)  # Must be a new directory, never clobber an older backup.
    containers, volumes = inventory()
    running = [container['Id'] for container in containers if container['State']['Running']]
    manifest = {'version': 1, 'project': PROJECT, 'volumes': volumes, 'config': [],
                'images': {c['Config']['Labels']['com.docker.compose.service']: c['Image'] for c in containers}}
    try:
        if running:
            # Global quiescence preserves cross-service intents, object references and captures.
            docker('stop', '--time', '60', *running, timeout=300)
        for index, volume in enumerate(volumes):
            filename = f'volume-{index:02d}.tar'
            docker('run', '--rm', '--network', 'none', '--mount', f"type=volume,source={volume['name']},target=/source,readonly",
                   '--mount', f'type=bind,source={target},target=/backup', HELPER,
                   'tar', '-cpf', '/backup/' + filename, '-C', '/source', '.', timeout=600)
            path = target / filename
            path.chmod(0o600)
            volume.update(file=filename, sha256=digest(path))
        config = target / 'config'
        config.mkdir(mode=0o700)
        for source in sorted(STATE.iterdir()):
            if not source.is_file() or not (source.name in ('environment.env', 'broker-definitions.json', 'owner.json', 'Caddyfile') or source.suffix in ('.crt', '.key', '.p12')):
                continue
            path = config / source.name
            path.write_bytes(source.read_bytes())
            path.chmod(0o600)
            manifest['config'].append({'file': source.name, 'sha256': digest(path)})
        private_json(target / 'manifest.json', manifest)
    finally:
        if running:
            docker('start', *running)
    print(f'Backup complete: {len(volumes)} persistent volumes; installation restarted; private directory {target}')


def validated_manifest(source):
    manifest = json.loads((source / 'manifest.json').read_text())
    if manifest.get('version') != 1 or manifest.get('project') != PROJECT:
        raise RuntimeError('Unsupported backup manifest')
    for entry in manifest['volumes'] + manifest['config']:
        name = entry['file']
        if Path(name).name != name or name in ('.', '..'):
            raise RuntimeError('Invalid backup filename')
        path = source / ('config' if entry in manifest['config'] else '') / name
        if path.is_symlink() or digest(path) != entry['sha256']:
            raise RuntimeError('Backup checksum mismatch')
    return manifest


def restore(source, prefix, start=False):
    source = source.resolve()
    manifest = validated_manifest(source)  # Validate everything before creating any volumes.
    if not re.fullmatch(r'streaming-restore-[a-z0-9-]{1,48}', prefix):
        raise RuntimeError('Prefix must start with streaming-restore- and contain only lowercase letters/digits/hyphens')
    restored = []
    for index, entry in enumerate(manifest['volumes']):
        name = f'{prefix}-{index:02d}'
        if subprocess.run(['docker', 'volume', 'inspect', name], capture_output=True).returncode == 0:
            raise RuntimeError('Refusing to overwrite an existing restore volume')
    for index, entry in enumerate(manifest['volumes']):
        name = f'{prefix}-{index:02d}'
        docker('volume', 'create', '--label', 'streaming.recovery=true', name)
        docker('run', '--rm', '--network', 'none', '--mount', f'type=volume,source={name},target=/restore',
               '--mount', f'type=bind,source={source},target=/backup,readonly', HELPER,
               'tar', '-xpf', '/backup/' + entry['file'], '-C', '/restore', timeout=600)
        restored.append({**entry, 'restoredName': name})
    output = source / (prefix + '.json')
    private_json(output, {'version': 1, 'volumes': restored})
    private_json(source / (prefix + '.compose.json'), {'volumes': {entry['logicalName']: {'external': True, 'name': entry['restoredName']} for entry in restored}})
    print(f'Restored {len(restored)} fresh volumes; original installation untouched. Mapping: {output}')
    if start:
        start_restored(source, prefix, manifest)


def start_restored(source, prefix, manifest):
    """Launch only fresh restored volumes on a separate network and unused loopback ports."""
    if not (source / 'config/Caddyfile').is_file() or not manifest.get('images'):
        raise RuntimeError('This older backup lacks runtime configuration; create a current backup')
    state = source / (prefix + '-state')
    state.mkdir(mode=0o700)
    for entry in manifest['config']:
        target = state / entry['file']
        shutil.copyfile(source / 'config' / entry['file'], target)
        target.chmod(0o600)
    env = dict(line.split('=', 1) for line in (state / 'environment.env').read_text().splitlines() if '=' in line)
    networks = docker('network', 'ls', '-q').split()
    occupied = [ipaddress.ip_network(config['Subnet']) for network in json.loads(docker('network', 'inspect', *networks))
                for config in (network.get('IPAM', {}).get('Config') or []) if config.get('Subnet')]
    candidates = (ipaddress.ip_network(f'192.168.{octet}.0/24') for octet in range(240, 255))
    subnet = next((candidate for candidate in candidates if not any(candidate.overlaps(existing) for existing in occupied if existing.version == 4)), None)
    if subnet is None:
        raise RuntimeError('No unused recovery subnet available')
    sockets = [socket.socket() for _ in range(3)]
    try:
        for sock in sockets:
            sock.bind(('127.0.0.1', 0))
        https, rtmps, mailpit = [sock.getsockname()[1] for sock in sockets]
    finally:
        for sock in sockets:
            sock.close()
    env.update(P1_STATE=str(state), P1_HTTPS_PORT=str(https), P1_RTMPS_PORT=str(rtmps), MAILPIT_UI_PORT=str(mailpit),
               LOCAL_HTTPS_BIND='127.0.0.1', LOCAL_PUBLIC_HOST='localhost', LOCAL_LAN_IP='127.0.0.1',
               WEB_ORIGIN=f'https://localhost:{https}', LOCAL_SUBNET=str(subnet),
               LOCAL_PROXY_IP=str(subnet.network_address + 2), LOCAL_GATEWAY_IP=str(subnet.network_address + 3),
               LOCAL_DYNAMIC_RANGE=str(list(subnet.subnets(prefixlen_diff=1))[1]))
    (state / 'environment.env').write_text(''.join(f'{key}={value}\n' for key, value in sorted(env.items())))
    (state / 'environment.env').chmod(0o600)
    override = json.loads((source / (prefix + '.compose.json')).read_text())
    override['services'] = {name: {'image': image, 'pull_policy': 'never'} for name, image in manifest['images'].items()}
    runtime = source / (prefix + '.runtime.json')
    private_json(runtime, override)
    docker('compose', '--env-file', str(state / 'environment.env'), '-p', prefix,
           '-f', str(ROOT / 'compose.yaml'), '-f', str(ROOT / 'infra/broker/compose.yaml'), '-f', str(runtime),
           'up', '-d', '--wait', '--wait-timeout', '240', timeout=360)
    print('Recovery stack ready: ' + env['WEB_ORIGIN'] + '; original installation remains independent')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('backup').add_argument('directory', type=Path)
    restore_parser = sub.add_parser('restore')
    restore_parser.add_argument('directory', type=Path)
    restore_parser.add_argument('--start', action='store_true', help='Start the restored installation independently on unused loopback ports')
    restore_parser.add_argument('--prefix', default='streaming-restore-' + uuid.uuid4().hex[:10])
    args = parser.parse_args()
    try:
        if args.action == 'backup':
            backup(args.directory)
        else:
            restore(args.directory, args.prefix, args.start)
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print('Backup/restore failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
