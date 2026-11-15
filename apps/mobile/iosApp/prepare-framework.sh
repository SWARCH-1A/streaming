#!/bin/sh
set -eu
cd "$SRCROOT/.."
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21)
  export JAVA_HOME
fi
case "$PLATFORM_NAME" in
  iphoneos) kotlin_target=IosArm64 ;;
  iphonesimulator) kotlin_target=IosSimulatorArm64 ;;
  *) echo "Unsupported iOS platform: $PLATFORM_NAME" >&2; exit 1 ;;
esac
./gradlew ":shared:linkDebugFramework$kotlin_target"
