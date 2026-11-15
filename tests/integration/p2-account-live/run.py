"""Real owner suspension: Core command -> Streaming fence -> HLS/source/session.

Requires the local P2 installation and P2_TEST_ACCOUNTS with ssr-owner/ssr-fan
fictitious fixtures. The fan is provisioned ADMIN using Core's operator script.
Never run against real accounts. The owner is reactivated in finally.
"""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import uuid

os.environ['P1_PROFILE'] = 'local'
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'p1-delivery'))
import verify as v
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'p2-fixtures'))
from accounts import fixture_account


def main():
    target, operator = fixture_account('ssr-owner'), fixture_account('ssr-fan')
    if not target or not operator or not target['handle'].startswith('rel_') or not operator['handle'].startswith('rel_'):
        raise RuntimeError('Requires explicitly provisioned fictitious release accounts')
    command = ['docker', 'exec', '-i', 'streaming-local-core-db-1', 'psql', '-U', 'core', '-d', 'core',
               '-v', 'target_user=' + operator['account']['userId']]
    subprocess.run(command, input=(v.ROOT / 'services/core/scripts/provision-admin.sql').read_text(),
                   text=True, capture_output=True, check=True)
    admin, owner, anon = v.Client(), v.Client(), v.Client()
    admin.login(operator['handle'], operator['password'])
    owner.login(target['handle'], target['password'])
    def current():
        cursor = ''
        while True:
            page = admin.call('/api/admin/users?limit=50' + ('&cursor=' + cursor if cursor else ''))
            found = next((item for item in page['items'] if item['userId'] == target['account']['userId']), None)
            if found:
                return found
            cursor = page['nextCursor']
            if not cursor:
                raise AssertionError('Fixture account missing from administration')
    def change(status):
        account = current()
        return admin.core('/api/admin/users/' + account['userId'] + '/status', 'PUT',
                          {'status': status, 'expectedVersion': account['accountVersion'], 'reason': 'Fictitious live revocation acceptance'},
                          {'Idempotency-Key': str(uuid.uuid4())})
    category = anon.call('/api/taxonomy')['categories'][0]['id']
    channel = owner.call('/api/channels/by-handle/' + target['handle'])['channel']['channelId']
    config = owner.call(f'/api/channels/{channel}/streams', 'POST',
                        {'title': 'Suspension acceptance', 'categoryId': category, 'tagIds': []},
                        {'Idempotency-Key': str(uuid.uuid4())})
    secret = owner.core('/api/streams/' + config['streamId'] + '/ingest-keys/rotate')['streamKey']
    config['ingestUrl'] = config['rtmpUrl'].rstrip('/') + '/' + secret
    publisher = None
    with tempfile.TemporaryFile() as log:
        try:
            publisher = v.publish(config, log)
            live = v.eventually(lambda: anon.call('/api/streams/' + config['streamId']), lambda s: s['availability'] == 'PLAYABLE', 90)
            sid = live['sessionId']
            manifest = anon.call('/hls/' + sid + '/index.m3u8', raw=True)
            assert b'#EXTM3U' in manifest
            change('SUSPENDED')
            started = time.monotonic()
            def denied(path):
                try:
                    anon.call(path, raw=True)
                    return False
                except AssertionError as error:
                    return 'got 404' in str(error) or 'got 410' in str(error)
            v.eventually(lambda: denied('/hls/' + sid + '/index.m3u8'), bool, 5)
            v.eventually(lambda: denied('/hls/' + sid + '/preview.jpg'), bool, 5)
            assert time.monotonic() - started <= 5
            print('PASS suspension rejects direct HLS and preview within five seconds', flush=True)
            # The reconnecting encoder must not create another session while suspended.
            time.sleep(3)
            assert denied('/api/streams/' + config['streamId'])
            publisher.terminate(); publisher.wait(20); publisher = None
            change('ACTIVE')
            old = v.eventually(lambda: anon.call('/api/streams/sessions/' + sid), lambda s: s['status'] == 'ENDED', 10)
            assert old['availability'] != 'PLAYABLE'
            assert denied('/hls/' + sid + '/index.m3u8')
            print('PASS reactivation does not revive the old session or HLS URL', flush=True)
            # A fresh login and ingest may create a new session under the new account revision.
            owner.login(target['handle'], target['password'])
            publisher = v.publish(config, log)
            fresh = v.eventually(lambda: anon.call('/api/streams/' + config['streamId']), lambda s: s['availability'] == 'PLAYABLE' and s['sessionId'] != sid, 90)
            publisher.terminate(); publisher.wait(20); publisher = None
            owner.call('/api/streams/sessions/' + fresh['sessionId'], 'DELETE', expect=204)
            print('PASS new authorized ingest uses a new session and released capacity', flush=True)
        finally:
            if publisher:
                publisher.terminate(); publisher.wait(20)
            if current()['status'] != 'ACTIVE':
                change('ACTIVE')


if __name__ == '__main__':
    main()
