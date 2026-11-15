"""Android acceptance against the real local P2 stack and a dedicated emulator.

Requires the debug APK, trusted development CA, adb, ffmpeg, Playwright's Python
requirements (shared verify client), and private fictitious P2_TEST_ACCOUNTS.
Resets only dev.swarch.vault on the explicitly selected emulator. No real users.
"""
from pathlib import Path
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
os.environ['P1_PROFILE'] = 'local'
sys.path.insert(0, str(ROOT / 'tests/integration/p1-delivery'))
sys.path.insert(0, str(ROOT / 'tests/integration/p2-fixtures'))
import verify as api
from accounts import fixture_account

PACKAGE = 'dev.swarch.vault'
ACTIVITY = PACKAGE + '/streaming.mobile.android.MainActivity'


class Android:
    def __init__(self, executable, serial):
        if not re.fullmatch(r'emulator-[0-9]+', serial):
            raise RuntimeError('Use a dedicated Android emulator, never a personal device')
        self.command = [executable, '-s', serial]
        if self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() != b'1':
            raise RuntimeError('The selected device is not an emulator')

    def adb(self, *args):
        result = subprocess.run([*self.command, *args], capture_output=True, timeout=30)
        # An input command may contain a fictitious password: never echo argv/stderr.
        if result.returncode:
            raise RuntimeError('Android operation failed')
        return result.stdout

    def dump(self):
        self.adb('shell', 'uiautomator', 'dump', '/sdcard/streaming-acceptance.xml')
        return ET.fromstring(self.adb('exec-out', 'cat', '/sdcard/streaming-acceptance.xml'))

    def find(self, text):
        return next((n for n in self.dump().iter('node') if n.get('text') == text or n.get('content-desc') == text), None)

    def wait(self, text, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self.find(text)
            if node is not None:
                return node
        raise AssertionError('Missing Android control: ' + text)

    def tap_node(self, node):
        left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        self.adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))

    def tap(self, text):
        self.tap_node(self.wait(text))

    def input(self, node, value):
        self.tap_node(node)
        self.adb('shell', 'input', 'text', shlex.quote(value))

    def fields(self):
        return [n for n in self.dump().iter('node') if n.get('class') == 'android.widget.EditText']

    def launch(self):
        self.adb('shell', 'am', 'start', '-n', ACTIVITY)

    def screenshot(self, path):
        path.write_bytes(self.adb('exec-out', 'screencap', '-p'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--reset-fixture-app', action='store_true', required=True,
                        help='Acknowledge resetting only Vault tv on this dedicated emulator')
    args = parser.parse_args()
    fan, source = fixture_account('ssr-fan'), fixture_account('replay-owner')
    if not fan or not source or not all(a['handle'].startswith('rel_') for a in (fan, source)):
        raise RuntimeError('Requires explicit rel_ fictitious fixtures')
    device = Android(args.adb, args.serial)
    artifacts = Path(tempfile.mkdtemp(prefix='streaming-mobile-'))
    device.adb('shell', 'pm', 'clear', PACKAGE)
    device.launch()
    device.tap('Iniciar sesión')
    device.wait('Correo o usuario')
    fields = device.fields()
    assert len(fields) == 2
    for field, value in zip(fields, (fan['handle'], fan['password'])):
        device.input(field, value)
    device.adb('shell', 'input', 'keyevent', '4')
    device.tap('Iniciar sesión')
    device.wait('Mi cuenta')
    device.adb('shell', 'am', 'force-stop', PACKAGE)
    device.launch()
    device.wait('Mi cuenta')
    print('PASS Android HTTPS login and Keystore session across process restart', flush=True)

    device.tap('Buscar')
    device.wait('Buscar canales o transmisiones')
    device.input(device.fields()[0], source['handle'])
    device.adb('shell', 'input', 'keyevent', '4')
    device.tap('@' + source['handle'])
    if device.find('Dejar de seguir') is not None:
        device.tap('Dejar de seguir')
    device.tap('Seguir canal')
    device.wait('Dejar de seguir')
    device.tap('Siguiendo')
    device.wait('@' + source['handle'])
    print('PASS Android Gateway search, channel, follow and followed list', flush=True)

    owner = api.Client()
    owner.login(source['handle'], source['password'])
    channel = owner.call('/api/channels/by-handle/' + source['handle'])
    cid = channel['channel']['channelId']
    catalogue = owner.call('/api/taxonomy')
    config = owner.call(f'/api/channels/{cid}/streams', 'POST',
                        {'title': 'Android acceptance', 'categoryId': catalogue['categories'][0]['id'], 'tagIds': []},
                        {'Idempotency-Key': str(uuid.uuid4())}, (200, 201))
    if not config.get('streamKey'):
        config['streamKey'] = owner.call('/api/streams/' + config['streamId'] + '/ingest-keys/rotate',
                                        'POST', headers={'Idempotency-Key': str(uuid.uuid4())})['streamKey']
    config['ingestUrl'] = config['rtmpUrl'].rstrip('/') + '/' + config['streamKey']
    log = tempfile.TemporaryFile()
    publisher, sid = api.publish(config, log), None
    try:
        live = api.eventually(lambda: owner.call('/api/streams/' + config['streamId']),
                              lambda item: item.get('availability') == 'PLAYABLE', 60)
        sid = live['sessionId']
        device.tap('Bandeja')
        device.wait(source['handle'] + ' está en vivo')
        device.tap('Marcar como leído')
        device.tap('Abrir canal')
        device.wait('@' + source['handle'])
        device.tap('Ver transmisión')
        device.wait('Enviar un mensaje', 45)
        text = 'AndroidRelease' + uuid.uuid4().hex[:8]
        device.input(device.fields()[0], text)
        device.tap('Enviar mensaje')
        device.adb('shell', 'input', 'keyevent', '4')
        api.eventually(lambda: any(text in n.get('text', '') for n in device.dump().iter('node')), bool, 15)
        def count():
            return owner.call('/api/streams/sessions/' + sid)['viewerCount']
        api.eventually(count, lambda value: value == 1, 15)
        device.screenshot(artifacts / 'live-chat.png')
        print('PASS Android live decoded frame/lease, Bearer WebSocket ACK, inbox/read and destination', flush=True)
        device.adb('shell', 'input', 'keyevent', '3')
        api.eventually(count, lambda value: value == 0, 10)
        device.launch()
        device.wait('Chat en vivo')
        api.eventually(count, lambda value: value == 1, 20)
        device.tap('Volver')
        api.eventually(count, lambda value: value == 0, 10)
        print('PASS Android background/resume/leave: viewer counts 1 → 0 → 1 → 0', flush=True)
    finally:
        publisher.terminate()
        publisher.wait(20)
        log.close()
        if sid:
            owner.call('/api/streams/sessions/' + sid, 'DELETE', expect=204)

    device.tap('Mi cuenta')
    device.tap('Cerrar sesión')
    device.wait('Iniciar sesión')
    device.adb('shell', 'am', 'force-stop', PACKAGE)
    device.launch()
    device.wait('Iniciar sesión')
    print('PASS Android logout remains cleared across process restart', flush=True)
    print('Artifacts: ' + str(artifacts), flush=True)
    print('VOD/seek/subtitles, push, physical devices and iOS require their separate acceptance.', flush=True)


if __name__ == '__main__':
    main()
