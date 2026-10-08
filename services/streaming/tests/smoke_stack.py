#!/usr/bin/env python3
"""Exercise the real P1 stack in an isolated Compose project (Docker + Python 3)."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return str(listener.getsockname()[1])


def wait_for(check, description, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + description)


def main():
    project = 'streaming-smoke-' + uuid.uuid4().hex[:12]
    env = os.environ.copy()
    for name in ('HTTP', 'HLS', 'RTMP', 'DB'):
        env['STREAMING_' + name + '_PORT'] = free_port()
    # Fixtures, restricted to this disposable project and never personal secrets.
    env['MEDIA_DATABASE_PASSWORD'] = 'smoke_media_password_before_rotation'
    stream_id = 'str_' + str(uuid.uuid4())
    key = 'smoke_ingest_key_only_for_disposable_database'
    image = env.get('STREAMING_SMOKE_IMAGE', project + '-streaming')
    http_url = 'http://127.0.0.1:' + env['STREAMING_HTTP_PORT']
    hls_url = 'http://127.0.0.1:' + env['STREAMING_HLS_PORT']

    with tempfile.TemporaryDirectory(prefix='streaming-smoke-') as directory:
        override = Path(directory) / 'override.yaml'

        def write_override(collision=False):
            override.write_text('services:\n  streaming:\n    image: ' + image
                                + '\n    restart: "no"\n'
                                + ('    environment:\n      MEDIA_BIND_ADDR: 0.0.0.0:8080\n'
                                   if collision else ''))

        write_override()
        compose = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'compose.yaml'),
                   '-f', str(override)]

        def run(*args, input=None, check=True, timeout=120):
            result = subprocess.run([*compose, *args], env=env, input=input,
                                    capture_output=True, text=True, timeout=timeout)
            if check and result.returncode:
                raise AssertionError('Compose command failed: ' + ' '.join(args[:3])
                                     + '\n' + result.stderr[-4000:])
            return result

        def sql(query, database='streaming'):
            return run('exec', '-T', 'db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'streaming', '-d', database, '-At', input=query).stdout.strip()

        def state(service):
            container = run('ps', '-a', '-q', service).stdout.strip()
            result = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', container],
                                    env=env, capture_output=True, text=True, check=True)
            return json.loads(result.stdout)

        def healthy():
            return state('streaming').get('Health', {}).get('Status') == 'healthy'

        def up():
            run('up', '-d', '--wait', '--wait-timeout', '90')

        def http(path):
            with urllib.request.urlopen(path, timeout=5) as response:
                return response.read()

        encoder = None
        try:
            config = json.loads(run('config', '--format', 'json').stdout)
            assert set(config['services']) == {'db', 'streaming', 'mediamtx'}
            if 'STREAMING_SMOKE_IMAGE' not in env:
                run('build', 'streaming', timeout=600)
            up()
            assert len(run('ps', '-a', '-q').stdout.splitlines()) == 3
            assert json.loads(http(http_url + '/health/ready'))['status'] == 'ready'
            for database in ('streaming', 'streaming_media'):
                assert sql('SELECT count(*) FROM _sqlx_migrations WHERE success;', database) == '1'
            # This CLI remains available inside the same container, with no daemon mode.
            run('exec', '-T', 'streaming', 'media-adapter', 'dead-letter', 'list')
            assert run('exec', '-T', 'streaming', 'media-adapter', check=False).returncode != 0
            print('PASS: exactly three containers, both migrations and operator CLI', flush=True)

            # Seed only this test-owned SQL store; Core/Chat are outside this media smoke.
            digest = hashlib.sha256(key.encode()).hexdigest()
            sql("INSERT INTO stream_configs(stream_id,channel_id,owner_user_id,title,category_id,"
                "catalog_labels,ingest_key_hash) VALUES ('" + stream_id + "','channel_smoke',"
                "'user_smoke','Smoke video','category_smoke','[{\"id\":\"category_smoke\","
                "\"name\":\"Smoke\",\"active\":true}]',decode('" + digest + "','hex'));" )
            encoder = subprocess.Popen([*compose, 'exec', '-T', 'streaming', 'ffmpeg',
                '-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi', '-i',
                'testsrc2=size=320x180:rate=15', '-f', 'lavfi', '-i', 'sine=frequency=440',
                '-t', '30', '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
                '-pix_fmt', 'yuv420p', '-g', '15', '-c:a', 'aac', '-f', 'flv',
                'rtmp://mediamtx:1935/live/' + stream_id + '?user=smoke&pass=' + key],
                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
            wait_for(lambda: sql("SELECT count(*) FROM stream_sessions WHERE status='LIVE';") == '1',
                     'real RTMP source becomes LIVE', seconds=25)
            session = sql("SELECT session_id FROM stream_sessions WHERE status='LIVE';")
            assert http(hls_url + '/hls/' + session + '/index.m3u8').startswith(b'#EXTM3U')
            run('exec', '-T', 'streaming', 'ffmpeg', '-hide_banner', '-loglevel', 'error',
                '-i', 'http://127.0.0.1:8888/hls/' + session + '/index.m3u8',
                '-frames:v', '1', '-f', 'null', '-', timeout=20)
            wait_for(lambda: int(sql('SELECT count(*) FROM media_callback_outbox '
                                    'WHERE delivered_at IS NOT NULL;', 'streaming_media')) >= 2,
                     'durable source/playback callbacks ACKed')
            callbacks = sql('SELECT count(*) FROM media_callback_outbox;', 'streaming_media')
            print('PASS: RTMP authorization, durable callbacks, public HLS and decoded frame', flush=True)

            # SIGTERM must stop API, HLS, and workers together, leaving the motor/SQL running.
            run('stop', 'streaming')
            assert state('streaming')['ExitCode'] == 0
            assert state('mediamtx')['Running'] and state('db')['Running']
            for port in (env['STREAMING_HTTP_PORT'], env['STREAMING_HLS_PORT']):
                try:
                    urllib.request.urlopen('http://127.0.0.1:' + port + '/health/live', timeout=2)
                except (urllib.error.URLError, ConnectionError, TimeoutError):
                    pass
                else:
                    raise AssertionError('Listener survived Streaming SIGTERM')
            _, encoder_error = encoder.communicate(timeout=35)
            # Docker also stops this exec-based fixture with its container.
            assert encoder.returncode in (0, 137, 143), (encoder.returncode, encoder_error)
            encoder = None
            up()
            assert sql('SELECT stream_id FROM stream_configs;') == stream_id
            assert sql('SELECT status FROM stream_sessions;') == 'ENDED'
            assert int(sql('SELECT count(*) FROM media_callback_outbox;', 'streaming_media')) >= int(callbacks)
            print('PASS: coordinated SIGTERM, persisted IDs/callbacks, no renewed session grace', flush=True)

            # Reuse the same volume and reconcile credentials without a one-shot container.
            run('down')
            env['MEDIA_DATABASE_PASSWORD'] = 'smoke_media_password_after_rotation'
            up()
            assert sql('SELECT stream_id FROM stream_configs;') == stream_id
            run('exec', '-T', '-e', 'PGPASSWORD=' + env['MEDIA_DATABASE_PASSWORD'], 'db',
                'psql', '-h', 'db', '-U', 'media_adapter', '-d', 'streaming_media',
                '-Atc', 'SELECT 1;')
            old_password = run('exec', '-T', '-e', 'PGPASSWORD=smoke_media_password_before_rotation',
                               'db', 'psql', '-h', 'db', '-U', 'media_adapter',
                               '-d', 'streaming_media', '-Atc', 'SELECT 1;', check=False)
            assert old_password.returncode != 0
            print('PASS: existing volume and Media password rotation', flush=True)

            # A fatal adapter bind error must terminate the whole process, not just HLS.
            write_override(collision=True)
            run('up', '-d', '--no-deps', '--force-recreate', 'streaming')
            wait_for(lambda: not state('streaming')['Running'], 'fatal adapter error stops Streaming')
            assert state('streaming')['ExitCode'] != 0
            assert state('mediamtx')['Running'] and state('db')['Running']
            write_override()
            run('up', '-d', '--no-deps', '--force-recreate', 'streaming')
            wait_for(healthy, 'stack recovers after adapter startup error')
            # Losing the dedicated control connection must also stop Media.
            sql("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' "
                "AND classid=60404 AND objid=1 AND database=(SELECT oid FROM pg_database "
                "WHERE datname=current_database());")
            wait_for(lambda: not state('streaming')['Running'], 'owner connection loss stops both modules')
            assert state('streaming')['ExitCode'] != 0
            print('PASS: control owner failure propagates to the combined process', flush=True)
            print('PASS: adapter failure propagates and corrected configuration recovers', flush=True)
        except Exception:
            print(run('logs', '--no-color', '--tail', '35', check=False).stdout, flush=True)
            raise
        finally:
            run('down', '-v', '--remove-orphans', check=False)
            if encoder is not None:
                try:
                    encoder.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    encoder.kill()
                    encoder.communicate()
            if 'STREAMING_SMOKE_IMAGE' not in env:
                subprocess.run(['docker', 'image', 'rm', image], env=env,
                               capture_output=True, check=False)


if __name__ == '__main__':
    main()
