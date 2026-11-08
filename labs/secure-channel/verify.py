"""Validate reassembled TCP conversations, not just strings in isolated packets."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def tshark(pcap, *arguments):
    return subprocess.run(['tshark', '-r', str(pcap), *arguments],
                          check=True, capture_output=True, text=True).stdout


def directions(raw):
    """tshark follow,tcp,raw: node0 is unindented, node1 is tab-indented."""
    buffers = [bytearray(), bytearray()]
    for line in raw.splitlines():
        match = re.fullmatch(r'(\t?)([0-9a-fA-F]+)', line)
        if match:
            buffers[bool(match[1])].extend(bytes.fromhex(match[2]))
    return [bytes(buffer) for buffer in buffers]


def unchunk(data):
    body = bytearray()
    while True:
        line, separator, data = data.partition(b'\r\n')
        if not separator:
            raise ValueError('Truncated chunk size')
        size = int(line.split(b';', 1)[0], 16)
        if size == 0:
            while True:
                trailer, separator, data = data.partition(b'\r\n')
                if not separator:
                    raise ValueError('Truncated chunk trailers')
                if not trailer:
                    return bytes(body), data
        if len(data) < size + 2 or data[size:size + 2] != b'\r\n':
            raise ValueError('Truncated chunk data')
        body.extend(data[:size])
        data = data[size + 2:]


def messages(data):
    """Decode HTTP/1.1 including Spring's chunked JSON responses."""
    result = []
    while data:
        head, separator, remainder = data.partition(b'\r\n\r\n')
        if not separator:
            raise ValueError('Truncated HTTP headers in reassembled capture')
        lines = head.decode('iso-8859-1').split('\r\n')
        headers = {}
        for line in lines[1:]:
            name, value = line.split(':', 1)
            headers.setdefault(name.lower(), []).append(value.strip())
        if headers.get('transfer-encoding') == ['chunked']:
            body, data = unchunk(remainder)
        else:
            length = int(headers.get('content-length', ['0'])[0])
            if len(remainder) < length:
                raise ValueError('Truncated HTTP body in reassembled capture')
            body, data = remainder[:length], remainder[length:]
        result.append((lines[0], headers, body))
    return result


def check_http(streams, credentials, identity):
    login = None
    profile = None
    for sides in streams:
        client, server = (messages(side) for side in sides)
        if len(client) != len(server):
            raise ValueError('Captured HTTP requests/responses do not match')
        for request, response in zip(client, server):
            if request[0] == 'POST /api/identity/sessions HTTP/1.1':
                submitted = json.loads(request[2])
                if submitted != {'login': credentials['email'], 'password': credentials['password']}:
                    raise ValueError('Captured credentials differ from the fictitious seed')
                if not request[1].get('x-xsrf-token') or not request[1].get('cookie'):
                    raise ValueError('Login is missing the CSRF token/cookie')
                if not response[0].startswith('HTTP/1.1 200 '):
                    raise ValueError('Captured login did not succeed')
                if json.loads(response[2])['userId'] != identity['userId']:
                    raise ValueError('Login identity mismatch')
                cookies = response[1].get('set-cookie', [])
                session = next((item for item in cookies if item.startswith('stream_session=')), None)
                if not session or 'httponly' not in session.lower() or re.search(r';\s*secure(?:;|$)', session, re.I):
                    raise ValueError('Unexpected HTTP laboratory session cookie flags')
                login = session.split(';', 1)[0]
            elif request[0] == 'GET /api/profile/me HTTP/1.1':
                profile = (request, response)
    if not login or not profile:
        raise ValueError('Capture must contain login and authenticated profile')
    request, response = profile
    cookie_values = [value.strip() for value in request[1].get('cookie', [''])[0].split(';')]
    if login not in cookie_values:
        raise ValueError('Profile does not use the session obtained by captured login')
    if not response[0].startswith('HTTP/1.1 200 ') or json.loads(response[2])['userId'] != identity['userId']:
        raise ValueError('Captured profile did not return the seeded identity')
    return {'reassembledLogin': True, 'exactFictitiousCredentialsReadable': True,
            'csrfPreserved': True, 'sessionCookieReadable': True,
            'authenticatedProfileMatches': True}


def verify(state, mode):
    folder = state / mode
    pcap = folder / 'traffic.pcap'
    metadata = json.loads((folder / 'capture.json').read_text())
    if hashlib.sha256(pcap.read_bytes()).hexdigest() != metadata['pcapSha256']:
        raise ValueError('PCAP changed since capture')
    client = json.loads((folder / 'client-result.json').read_text())
    if client != {'mode': mode, 'csrfStatus': 200, 'loginStatus': 200,
                  'profileStatus': 200, 'identityMatches': True}:
        raise ValueError('The real client flow was not successful')
    identifiers = sorted(set(tshark(pcap, '-Y', 'tcp.port == 8081',
                                    '-T', 'fields', '-e', 'tcp.stream').split()))
    if not identifiers:
        raise ValueError('Capture has no traffic on the selected connector')
    streams = []
    transcript = []
    for identifier in identifiers:
        raw = tshark(pcap, '-q', '-z', f'follow,tcp,raw,{identifier}')
        streams.append(directions(raw))
        transcript.append(tshark(pcap, '-q', '-z', f'follow,tcp,ascii,{identifier}'))
    (folder / 'follow-streams.txt').write_text('\n'.join(transcript))
    if mode == 'http':
        credentials = json.loads((state / 'credentials.json').read_text())
        identity = json.loads((state / 'identity.json').read_text())
        checks = check_http(streams, credentials, identity)
    else:
        handshake = tshark(pcap, '-d', 'tcp.port==8081,tls', '-Y', 'tls.handshake.type == 1',
                           '-T', 'fields', '-e', 'frame.number').strip()
        # TLS 1.3 hides the real type: Wireshark exposes the outer record as opaque_type.
        encrypted = tshark(pcap, '-d', 'tcp.port==8081,tls', '-Y',
                           'tls.record.content_type == 23 || tls.record.opaque_type == 23',
                           '-T', 'fields', '-e', 'frame.number').strip()
        marker = b'LAB5_CANAL_INSEGURO'
        if not handshake or not encrypted or any(marker in side for pair in streams for side in pair):
            raise ValueError('Expected TLS handshake and encrypted application records')
        checks = {'tlsClientHello': True, 'encryptedApplicationRecords': True,
                  'clientFlowWithExplicitCa': True, 'markerNotReadable': True,
                  'negativeCaAndHostnameTests': 'PENDING GROUP 3'}
    report = {**metadata, 'tcpStreams': len(identifiers), 'checks': checks}
    (folder / 'verification.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'mode': mode, 'tcpStreams': len(identifiers), 'checks': checks}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', required=True, choices=['http', 'https'])
    args = parser.parse_args()
    verify(Path('/state'), args.mode)
