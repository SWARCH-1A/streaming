"""Real four-track Enhanced RTMPS -> HLS selection -> native capture -> four-track VOD.

Requires the local installation, a built publisher, a source-encoded MPEG-TS file,
and P2_TEST_ACCOUNTS with the explicitly fictitious ssr-owner. No server transcoding.
"""
import argparse
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import uuid

os.environ['P1_PROFILE'] = 'local'
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'p1-delivery'))
import verify as v
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'p2-fixtures'))
from accounts import fixture_account
from playwright.sync_api import sync_playwright


def variants(master):
    return [line.strip() for line in master.splitlines() if line and not line.startswith('#')]



def verify_vod(vod_id):
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(ignore_https_errors=True)
        page.goto(v.ORIGIN + '/vod/' + vod_id)
        page.wait_for_function("document.querySelector('video')?.readyState>=2", timeout=45000)
        page.locator('video').evaluate('(v)=>{v.muted=true;return v.play()}')
        picker = page.get_by_role('combobox', name='Calidad', exact=True)
        picker.wait_for()
        options = picker.locator('option').evaluate_all('(options)=>options.map(o=>({value:o.value,label:o.textContent}))')
        assert len(options) == 5
        for option in options:
            if option['value'] == '-1': continue
            height = int(re.search(r'(\d+)p',option['label']).group(1))
            picker.select_option(option['value'])
            page.wait_for_function('(height)=>document.querySelector("video")?.videoHeight===height', arg=height, timeout=25000)
        page.wait_for_function('document.querySelector("video").webkitAudioDecodedByteCount > 0', timeout=15000)
        page.locator('video').evaluate('(v)=>{v.currentTime=20}')
        page.wait_for_function('document.querySelector("video").currentTime > 21 && !document.querySelector("video").seeking', timeout=15000)
        picker.select_option('-1')
        assert picker.input_value() == '-1'
        print('PASS READY published VOD decodes all four recorded source qualities', flush=True)
        print('PASS decoded AAC audio, VOD seek and return to automatic selection', flush=True)
        print('Review fixture: ' + v.ORIGIN + '/vod/' + vod_id, flush=True)
        browser.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publisher', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--vod-only')
    parser.add_argument('--reconnect', action='store_true', help='Reconnect all four tracks in the same live session before final recording')
    args = parser.parse_args()
    if args.vod_only:
        if not re.fullmatch(r'vod_[a-f0-9]{32}', args.vod_only): raise ValueError('Invalid VOD fixture ID')
        verify_vod(args.vod_only)
        return
    if not args.publisher or not args.source: parser.error('--publisher and --source are required for live capture')
    account = fixture_account('ssr-owner')
    if not account or not account['handle'].startswith('rel_'):
        raise RuntimeError('Explicitly fictitious local account required')
    owner, anon = v.Client(), v.Client()
    owner.login(account['handle'], account['password'])
    channel = owner.call('/api/channels/by-handle/' + account['handle'])['channel']['channelId']
    category = anon.call('/api/taxonomy')['categories'][0]['id']
    config = owner.call(f'/api/channels/{channel}/streams', 'POST',
                        {'title': 'Cuatro calidades reales', 'categoryId': category, 'tagIds': []},
                        {'Idempotency-Key': str(uuid.uuid4())}, expect=(200,201))
    owner.call('/api/streams/' + config['streamId'], 'PATCH', {'title': 'Cuatro calidades reales'},
               {'Idempotency-Key': str(uuid.uuid4())})
    secret = owner.core('/api/streams/' + config['streamId'] + '/ingest-keys/rotate')['streamKey']
    target = config['rtmpUrl'].rstrip('/') + '/' + secret
    publisher, sid = None, None
    with tempfile.TemporaryFile() as private_log:
        try:
            publisher = subprocess.Popen([str(args.publisher), str(args.source), str(v.CA)], stdin=subprocess.PIPE,
                                         stdout=private_log, stderr=private_log, text=True)
            publisher.stdin.write(target + '\n'); publisher.stdin.close()
            stream = v.eventually(lambda: anon.call('/api/streams/' + config['streamId']),
                                  lambda s: s['availability'] == 'PLAYABLE', 90)
            sid = stream['sessionId']
            renditions = v.eventually(lambda: anon.call(f'/api/streams/sessions/{sid}/renditions'),
                                     lambda body: len(body['renditions']) == 4, 30)
            heights = {r['height'] for r in renditions['renditions']}
            assert heights == {720,540,360,180} and 1080 not in heights
            master = anon.call(f'/hls/{sid}/index.m3u8', raw=True).decode()
            assert len(variants(master)) == 4 and secret not in master
            for path in variants(master):
                raw = anon.call(f'/hls/{sid}/' + path, raw=True).decode()
                assert '#EXTINF:' in raw and '#EXT-X-MAP:' in raw
            print('PASS four source resolutions in real HLS; no invented 1080p or ingest secret', flush=True)
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = browser.new_page(ignore_https_errors=True)
                page.goto(v.ORIGIN + '/watch/' + config['streamId'])
                page.wait_for_function("document.querySelector('video')?.readyState >= 2", timeout=45000)
                page.locator('video').evaluate('(v) => { v.muted=true; return v.play(); }')
                picker = page.get_by_role('combobox', name='Calidad', exact=True)
                picker.wait_for()
                options = picker.locator('option').evaluate_all('(options)=>options.map(o=>({value:o.value,label:o.textContent}))')
                assert len(options) == 5
                for option in options:
                    if not option['value']:
                        continue
                    height = int(re.search(r'(\d+)p', option['label']).group(1))
                    before = page.locator('video').evaluate('(v)=>v.currentTime')
                    picker.select_option(option['value'])
                    page.wait_for_function('(height)=>document.querySelector("video")?.videoHeight===height', arg=height, timeout=25000)
                    assert page.locator('video').evaluate('(v)=>v.currentTime') >= before - 1
                picker.select_option('')
                assert anon.call('/api/streams/' + config['streamId'])['sessionId'] == sid
                print('PASS decoded frames at all four manual qualities and automatic mode, continuous time and one session', flush=True)
                browser.close()
            if args.reconnect:
                assert publisher.poll() is None, 'Source ended before the reconnect test; use a longer fixture'
                publisher.terminate(); publisher.wait(20)
                v.eventually(lambda: anon.call('/api/streams/' + config['streamId']),
                    lambda state: state['sessionId'] == sid and state['availability'] == 'RECONNECTING', 10)
                publisher = subprocess.Popen([str(args.publisher), str(args.source), str(v.CA)], stdin=subprocess.PIPE,
                                             stdout=private_log, stderr=private_log, text=True)
                publisher.stdin.write(target + '\n'); publisher.stdin.close()
                v.eventually(lambda: anon.call('/api/streams/' + config['streamId']),
                    lambda state: state['sessionId'] == sid and state['availability'] == 'PLAYABLE', 30)
                assert len(anon.call(f'/api/streams/sessions/{sid}/renditions')['renditions']) == 4
                assert len(variants(anon.call(f'/hls/{sid}/index.m3u8', raw=True).decode())) == 4
                print('PASS reconnect preserves the session and all four source qualities', flush=True)
            publisher.wait(100)
            assert publisher.returncode == 0, 'Real multi-track publisher failed'
            publisher = None
            owner.call('/api/streams/sessions/' + sid, 'DELETE', expect=204)
            vod = v.eventually(lambda: next((item for item in owner.call('/api/me/vods')['items'] if item['sessionId'] == sid), None),
                               lambda item: item and item['processingState'] in ('READY','FAILED'), 300)
            assert vod['processingState'] == 'READY', 'Native multi-quality recording failed: ' + str(vod.get('failureCode'))
            if vod['publicationState'] != 'PUBLISHED':
                vod = owner.core('/api/vods/' + vod['vodId'] + '/publication', body={'expectedVersion':vod['version']},
                                 headers={'Idempotency-Key':str(uuid.uuid4())})
            if args.reconnect:
                timeline = anon.call('/api/vods/' + vod['vodId'] + '/timeline')
                generations = sorted({interval['sourceGeneration'] for interval in timeline['intervals']})
                assert len(generations) == 2 and generations[1] == generations[0] + 1
                print('PASS recorded timeline retains both consecutive source generations', flush=True)
            verify_vod(vod['vodId'])
        finally:
            if publisher and publisher.poll() is None:
                publisher.terminate(); publisher.wait(15)
            private_log.seek(0)
            diagnostics = private_log.read().decode(errors='replace')
            if diagnostics:
                print(diagnostics[-2000:], flush=True)
            if sid:
                owner.call('/api/streams/sessions/' + sid, 'DELETE', expect=204)


if __name__ == '__main__':
    main()
