"""Isolated secure-channel laboratory; never calls the root Compose deployment."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
STATE = LAB / '.state'


def execute(command, *, capture=False, check=True, env=None):
    return subprocess.run(command, cwd=ROOT, env=env, text=True,
                          capture_output=capture, check=check)


def initialize():
    if (STATE / 'config.json').exists():
        print('Already initialized. Existing account, data and secrets preserved.')
        return
    STATE.mkdir(exist_ok=True, mode=0o700)
    (STATE / 'tls').mkdir(exist_ok=True)
    suffix = secrets.token_hex(4)
    config = {'project': 'vault-lab5-' + suffix, 'mode': 'http',
              'baseCommit': execute(['git', 'rev-parse', 'HEAD'], capture=True).stdout.strip()}
    (STATE / 'environment.env').write_text(
        f'LAB_PROJECT={config["project"]}\nLAB_DB_PASSWORD={secrets.token_urlsafe(32)}\n'
        f'LAB_HMAC_SECRET={secrets.token_urlsafe(48)}\n', encoding='utf-8')
    credentials = {'email': f'lab5_{suffix}@example.test', 'handle': f'lab5_{suffix}',
                   'password': 'LAB5_CANAL_INSEGURO_' + secrets.token_urlsafe(12),
                   'idempotencyKey': str(uuid.uuid4())}
    (STATE / 'credentials.json').write_text(json.dumps(credentials, indent=2), encoding='utf-8')
    save_config(config)  # Only mark initialization complete after all private files exist.
    print('Initialized isolated project ' + config['project'] + '. Secrets remain in ignored .state/.')


def save_config(config):
    (STATE / 'config.json').write_text(json.dumps(config, indent=2), encoding='utf-8')


def configuration():
    if not (STATE / 'config.json').exists():
        raise RuntimeError('Run init first.')
    config = json.loads((STATE / 'config.json').read_text(encoding='utf-8'))
    if not re.fullmatch(r'vault-lab5-[0-9a-f]{8}', config.get('project', '')):
        raise RuntimeError('Invalid isolated lab project name; refusing Docker operation.')
    if config.get('mode') not in ('http', 'https'):
        raise RuntimeError('Invalid lab mode.')
    return config


def compose(args, *, mode=None, capture=False, check=True):
    config = configuration()
    mode = mode or config['mode']
    env = dict(os.environ)
    for name in list(env):
        if name.startswith('LAB_'):
            del env[name]
    # Do not inherit a TLS switch from an unrelated personal deployment.
    env.update(LAB_TLS_ENABLED='true' if mode == 'https' else 'false',
               LAB_TLS_KEYSTORE='/tls/core.p12' if mode == 'https' else '',
               LAB_TLS_PASSWORD='', LAB_ORIGIN=f'{mode}://localhost:18081')
    if mode == 'https':
        needed = ('core.p12', 'ca.crt', 'keystore-password.txt')
        if any(not (STATE / 'tls' / name).is_file() for name in needed):
            raise RuntimeError('HTTPS requires .state/tls/{core.p12,ca.crt,keystore-password.txt}; see README.')
        env['LAB_TLS_PASSWORD'] = (STATE / 'tls' / 'keystore-password.txt').read_text().strip()
    command = ['docker', 'compose', '--project-name', config['project'],
               '--env-file', str(STATE / 'environment.env'), '-f', str(LAB / 'compose.yaml')]
    return execute(command + args, capture=capture, check=check, env=env)


def client(action, mode, *, capture=False, check=True):
    return compose(['run', '--rm', '--no-deps', 'client', '/lab/client.py', action,
                    '--mode', mode], mode=mode, capture=capture, check=check)


def up(mode):
    compose(['up', '-d', '--wait', '--wait-timeout', '120', 'core-lab'], mode=mode)
    config = configuration()
    config['mode'] = mode
    save_config(config)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = client('health', mode, capture=True, check=False)
        if result.returncode == 0:
            print(f'Core UP: {mode}://localhost:18081 (only loopback).')
            return
        time.sleep(2)
    raise RuntimeError('Core readiness failed. Run logs; do not ignore this error.')


def capture_traffic(mode):
    config = configuration()
    if mode != config['mode']:
        raise RuntimeError(f'Active mode is {config["mode"]}; first run up --mode {mode}.')
    if not (STATE / 'identity.json').exists():
        raise RuntimeError('Run seed before capturing.')
    folder = STATE / mode
    folder.mkdir(exist_ok=True)
    for name in ('traffic.pcap', 'capture.json', 'verification.json', 'follow-streams.txt'):
        (folder / name).unlink(missing_ok=True)
    observer = config['project'] + '-capture'
    compose(['run', '-d', '--no-deps', '--name', observer, 'observer', '-i', 'any',
             '-p', '-s', '0', '-U', '-w', f'/state/{mode}/traffic.pcap', 'tcp', 'port', '8081'], mode=mode)
    try:
        for _ in range(40):
            logs = execute(['docker', 'logs', observer], capture=True, check=False)
            if 'listening on' in logs.stderr + logs.stdout:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('Observer did not become ready.')
        client('exercise', mode)
        time.sleep(1)
    finally:
        execute(['docker', 'stop', '--signal', 'SIGINT', '--timeout', '10', observer], capture=True)
        logs = execute(['docker', 'logs', observer], capture=True)
        (folder / 'tcpdump.log').write_text(logs.stdout + logs.stderr)
        execute(['docker', 'rm', observer], capture=True)
    proof = {'mode': mode, 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
             'baseCommit': config['baseCommit'], 'testedCommit': execute(
                 ['git', 'rev-parse', 'HEAD'], capture=True).stdout.strip(),
             'trackedDiffSha256': hashlib.sha256(execute(
                 ['git', 'diff', 'HEAD'], capture=True).stdout.encode()).hexdigest(),
             'pcapSha256': hashlib.sha256((folder / 'traffic.pcap').read_bytes()).hexdigest()}
    sources = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
               for path in sorted(LAB.iterdir()) if path.is_file()}
    proof['labSourcesSha256'] = sources
    (folder / 'capture.json').write_text(json.dumps(proof, indent=2))
    print(f'Capture saved locally: {folder / "traffic.pcap"}. Contains fictitious credentials and sessions.')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'build', 'up', 'status', 'logs', 'seed', 'capture', 'verify', 'down'])
    parser.add_argument('--mode', choices=['http', 'https'])
    args = parser.parse_args()
    if args.action == 'init':
        initialize()
        return
    config = configuration()
    mode = args.mode or config['mode']
    if args.action == 'build':
        compose(['build', 'core-lab', 'client'], mode=mode)
    elif args.action == 'up':
        up(mode)
    elif args.action == 'status':
        compose(['ps', '-a'], mode='http')  # Diagnostics/cleanup need no TLS files.
    elif args.action == 'logs':
        compose(['logs', '--tail', '100', 'core-lab', 'core-db'], mode='http')
    elif args.action == 'seed':
        client('seed', mode)
    elif args.action == 'capture':
        capture_traffic(mode)
    elif args.action == 'verify':
        compose(['run', '--rm', '--no-deps', 'client', '/lab/verify.py', '--mode', mode], mode=mode)
    else:
        compose(['down', '--remove-orphans'], mode='http')
        print('Lab containers stopped. Dedicated volumes, certificates and evidence preserved.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
