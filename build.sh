#!/usr/bin/env bash
# 一键编译 APK。用法：./build.sh [assembleDebug|assembleRelease]
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/work/empty/.toolchain/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/work/empty/.toolchain/gradle-home}"
if [ -z "${JAVA_HOME:-}" ]; then
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    export JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi
echo "JAVA_HOME=$JAVA_HOME"
echo "SDK=$SDK"

echo "sdk.dir=$SDK" > local.properties

TASK="${1:-assembleDebug}"
./gradlew "$TASK" --console=plain

echo
echo "APK:"
find app/build/outputs/apk -name "*.apk" -print
