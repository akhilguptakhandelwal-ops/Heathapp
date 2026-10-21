#!/usr/bin/env bash
# Robolectric checks for FrameCast. Screenshots land in test/target/shots/.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
CACHE="$ROOT/.cache"
mkdir -p "$CACHE"
ANDROID_JAR="${ANDROID_JAR:-$CACHE/android-34.jar}"
if [ ! -f "$ANDROID_JAR" ]; then
  curl -fsSL -o "$ANDROID_JAR" https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
fi
AXT="$CACHE/androidx-test/androidx-test.jar"
if [ ! -f "$AXT" ]; then
  python3 tools/build_androidx_test.py "$CACHE/androidx-test" "$ANDROID_JAR" "$PWD/tools/androidx-test-classes.txt"
fi

mkdir -p target/gen
aapt package -f -m -J target/gen -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -A "$ROOT/assets" \
  -I "$ANDROID_JAR" --min-sdk-version 26 --target-sdk-version 34 -F target/resources.ap_
mkdir -p src/test/resources/com/android/tools
cat > src/test/resources/com/android/tools/test_config.properties <<PROPS
android_merged_manifest=$ROOT/AndroidManifest.xml
android_resource_apk=$PWD/target/resources.ap_
android_merged_assets=$ROOT/assets
android_custom_package=com.akhil.framecast
PROPS
mvn -q -B test -Dandroid.jar="$ANDROID_JAR" -Dandroidx.test.jar="$AXT" "$@"
