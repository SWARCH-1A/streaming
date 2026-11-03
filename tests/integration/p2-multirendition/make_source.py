"""Encode a disposable four-quality source before publishing; the server never encodes it."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--seconds', type=int, default=75)
    args = parser.parse_args()
    if not 60 <= args.seconds <= 180:
        parser.error('--seconds must be between 60 and 180')
    if args.output.exists():
        parser.error('output must be a new disposable file')
    subprocess.run([
        'ffmpeg', '-hide_banner', '-loglevel', 'error',
        '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=25',
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
        '-filter_complex', '[0:v]split=4[v0][s1][s2][s3];[s1]scale=960:540[v1];[s2]scale=640:360[v2];[s3]scale=320:180[v3]',
        '-map', '[v0]', '-map', '[v1]', '-map', '[v2]', '-map', '[v3]', '-map', '1:a',
        '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
        '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-g', '50', '-keyint_min', '50', '-sc_threshold', '0',
        '-c:a', 'aac', '-b:a', '96k', '-t', str(args.seconds), '-f', 'mpegts', str(args.output),
    ], check=True)


if __name__ == '__main__':
    main()
