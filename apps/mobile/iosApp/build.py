"""Build the native UIKit host; requires macOS, Xcode and an arm64 JDK 21+."""
import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]

def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', default='https://localhost:3445')
    parser.add_argument('--device', action='store_true', help='Compile for a physical arm64 iPhone; requires signing for installation.')
    parser.add_argument('--sign-identity', help='Apple signing identity; use a local provisioning profile for physical devices.')
    parser.add_argument('--provisioning-profile', type=Path)
    args = parser.parse_args()
    origin = urlsplit(args.origin)
    if origin.scheme != 'https' or not origin.hostname or origin.username or origin.password or origin.path not in ('', '/') or origin.query or origin.fragment:
        parser.error('--origin must be a bare HTTPS origin')
    platform = 'IosArm64' if args.device else 'IosSimulatorArm64'
    sdk = 'iphoneos' if args.device else 'iphonesimulator'
    target = 'arm64-apple-ios17.2' + ('' if args.device else '-simulator')
    run(ROOT / 'gradlew', f':shared:linkDebugFramework{platform}')
    framework = ROOT / 'shared/build/bin' / (platform[0].lower() + platform[1:]) / 'debugFramework'
    bundle = ROOT / 'build/ios' / sdk / 'VaultTV.app'
    if bundle.exists():
        shutil.rmtree(bundle)
    bundle.mkdir(parents=True)
    sdk_path = subprocess.check_output(['xcrun', '--sdk', sdk, '--show-sdk-path'], text=True).strip()
    run('xcrun', '--sdk', sdk, 'swiftc', '-sdk', sdk_path, '-target', target,
        '-DDEBUG', '-module-name', 'VaultTV', '-F', framework, '-framework', 'VaultMobile', '-framework', 'UIKit',
        '-framework', 'Metal', '-framework', 'CoreText', '-framework', 'QuartzCore',
        '-framework', 'CoreGraphics', '-framework', 'Security', '-framework', 'AVFoundation',
        '-framework', 'AVKit', '-lc++', '-lsqlite3', ROOT / 'iosApp/main.swift', '-o', bundle / 'VaultTV')
    info = plistlib.loads((ROOT / 'iosApp/Info.plist').read_bytes())
    info['VaultApiOrigin'] = args.origin.rstrip('/')
    info['CFBundleSupportedPlatforms'] = ['iPhoneOS' if args.device else 'iPhoneSimulator']
    (bundle / 'Info.plist').write_bytes(plistlib.dumps(info))
    # Compose loads these names from NSBundle, with the generated package prefix.
    resources = ROOT / 'shared/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources'
    shutil.copytree(resources, bundle / 'compose-resources/composeResources/streaming.mobile.resources')
    if args.provisioning_profile:
        shutil.copyfile(args.provisioning_profile, bundle / 'embedded.mobileprovision')
    if not args.device or args.sign_identity:
        run('codesign', '--force', '--sign', args.sign_identity or '-', bundle)
    print(f'Built {bundle}')
    if args.device and not args.sign_identity:
        print('Unsigned device build. Configure Apple signing/provisioning before installation.')

if __name__ == '__main__':
    main()
