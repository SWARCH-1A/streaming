"""Read-only acceptance of a freshly restored local P2 installation.

Run backup.py restore --start first. Requires P2_TEST_ACCOUNTS with the fictitious
replay-owner captured in the backup. Never changes or deletes a VOD.
"""
import argparse
import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tests/integration/p2-fixtures'))
from accounts import fixture_account
from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup', required=True, type=Path)
    parser.add_argument('--prefix', required=True)
    args = parser.parse_args()
    if not args.prefix.startswith('streaming-restore-') or Path(args.prefix).name != args.prefix:
        raise RuntimeError('Requires an isolated restoration prefix')
    state = args.backup.resolve() / (args.prefix + '-state')
    spec = importlib.util.spec_from_file_location('recovery_replay', ROOT / 'tests/integration/p2-chat-replay/run.py')
    api = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(api)
    api.configure(state, args.prefix)
    account = fixture_account('replay-owner')
    if not account or not account['handle'].startswith('rel_'):
        raise RuntimeError('Requires explicitly fictitious recovery fixtures')
    owner, anon = api.Client(), api.Client()
    owner.mutate('/api/identity/sessions', body={'login': account['handle'], 'password': account['password']})
    _, library = owner.call('/api/me/vods')
    vod = next((item for item in library['items'] if item['processingState'] == 'READY' and item['publicationState'] == 'PUBLISHED'), None)
    assert vod, 'The backup must contain a READY published fixture VOD'
    _, timeline = anon.call('/api/vods/' + vod['vodId'] + '/timeline')
    assert timeline['durationMs'] == vod['durationMs']
    _, page = anon.call(f"/api/chat/vods/{vod['vodId']}/messages?fromMs=0&toMs={min(vod['durationMs'],300000)}&limit=50")
    assert page['mappingVersion'] == timeline['mappingVersion'] and page['items']
    assert all(item['text'] != 'dos (será retirado)' for item in page['items'])
    print('PASS restored Core login, VOD metadata/timeline and moderated Chat replay', flush=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        context = browser.new_context(ignore_https_errors=True)
        screen = context.new_page()
        screen.goto(api.ORIGIN + '/vod/' + vod['vodId'])
        screen.wait_for_function("document.querySelector('video')?.readyState >= 2", timeout=45000)
        screen.locator('video').evaluate('(video) => { video.muted = true; return video.play(); }')
        screen.wait_for_function("document.querySelector('video')?.currentTime > 0.5", timeout=30000)
        assert screen.locator('video').evaluate('(video) => video.videoWidth > 0')
        print('PASS restored MinIO references, media authorization and decoded VOD frames in Chromium', flush=True)
        browser.close()


if __name__ == '__main__':
    main()
