"""Real Core requests; this runs in the disposable client container, not the host."""
import argparse
import json
import os
from pathlib import Path
import subprocess

STATE = Path('/state')


def request(mode, label, method, path, *, body=None, headers=None, cookies=None):
    origin = f'{mode}://core-lab:8081'
    out = STATE / mode
    out.mkdir(exist_ok=True)
    target = out / label
    command = ['curl', '--silent', '--show-error', '--connect-timeout', '5',
               '--max-time', '30', '--noproxy', '*', '--request', method,
               '--output', str(target.with_suffix('.json')),
               '--dump-header', str(target.with_suffix('.headers')),
               '--write-out', '%{http_code}']
    if mode == 'https':
        command += ['--cacert', '/state/tls/ca.crt']
    if cookies:
        command += ['--cookie', str(cookies), '--cookie-jar', str(cookies)]
    if body is not None:
        payload = target.with_suffix('.request.json')
        payload.write_text(json.dumps(body), encoding='utf-8')
        command += ['--header', 'Content-Type: application/json',
                    '--data-binary', '@' + str(payload)]
    for name, value in (headers or {}).items():
        command += ['--header', f'{name}: {value}']
    result = subprocess.run(command + [origin + path], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{label}: curl {result.returncode}: {result.stderr.strip()}')
    status = int(result.stdout)
    text = target.with_suffix('.json').read_text(encoding='utf-8')
    data = json.loads(text) if text else None
    return status, data


def run(mode, action):
    if action == 'health':
        status, data = request(mode, 'health', 'GET', '/actuator/health')
        if status != 200 or data.get('status') != 'UP':
            raise RuntimeError(f'Core not ready: {status}')
        return
    credentials = json.loads((STATE / 'credentials.json').read_text())
    if action == 'seed':
        status, data = request(mode, 'registration', 'POST', '/api/identity/registrations',
                               body={k: credentials[k] for k in ('email', 'handle', 'password')},
                               headers={'Idempotency-Key': credentials['idempotencyKey']})
        if status != 201:
            raise RuntimeError(f'Registration expected 201, got {status}: {data}')
        (STATE / 'identity.json').write_text(json.dumps(data, indent=2))
        print('Registration: 201. Fictitious account ready (idempotent).')
        return
    identity = json.loads((STATE / 'identity.json').read_text())
    cookies = STATE / mode / 'cookies.txt'
    cookies.parent.mkdir(exist_ok=True)
    cookies.unlink(missing_ok=True)  # A fresh session for each measured execution.
    status, csrf = request(mode, 'csrf', 'GET', '/api/identity/csrf', cookies=cookies)
    if status != 200 or csrf['headerName'] != 'X-XSRF-TOKEN':
        raise RuntimeError('CSRF token not available')
    status, login = request(mode, 'login', 'POST', '/api/identity/sessions', cookies=cookies,
                            body={'login': credentials['email'], 'password': credentials['password']},
                            headers={csrf['headerName']: csrf['token']})
    if status != 200 or login.get('userId') != identity['userId']:
        raise RuntimeError(f'Login expected 200 and correct identity, got {status}')
    status, profile = request(mode, 'profile', 'GET', '/api/profile/me', cookies=cookies)
    if status != 200 or profile.get('userId') != identity['userId']:
        raise RuntimeError(f'Profile expected 200 and correct identity, got {status}')
    proof = {'mode': mode, 'csrfStatus': 200, 'loginStatus': 200,
             'profileStatus': 200, 'identityMatches': True}
    (STATE / mode / 'client-result.json').write_text(json.dumps(proof, indent=2))
    print('CSRF: 200; login: 200; authenticated profile: 200; identity matches seed.')


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['health', 'seed', 'exercise'])
    parser.add_argument('--mode', choices=['http', 'https'], default='http')
    args = parser.parse_args()
    run(args.mode, args.action)
