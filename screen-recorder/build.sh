#!/usr/bin/env bash
# Builds FrameCast.apk (or FrameCast-Full.apk with FULL=1) without Gradle.
# Needs: javac (JDK 17+), aapt, dalvik-exchange (dx), zipalign, apksigner
#   Ubuntu: sudo apt-get install aapt dalvik-exchange zipalign apksigner
# and an Android platform jar (API 34). Set ANDROID_JAR, or it is downloaded.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.$VERSION_CODE}"
OUT=build

if [ -z "${ANDROID_JAR:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
  else
    ANDROID_JAR="$PWD/.cache/android-34.jar"
    if [ ! -f "$ANDROID_JAR" ]; then
      mkdir -p .cache
      curl -fsSL -o "$ANDROID_JAR" https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
    fi
  fi
fi
DX=$(command -v dalvik-exchange || command -v dx)

# FULL=1 adds the accessibility service (real click / scroll in other apps).
FULL="${FULL:-0}"
APK_NAME=FrameCast.apk
[ "$FULL" = 1 ] && APK_NAME=FrameCast-Full.apk

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
MANIFEST="$OUT/AndroidManifest.xml"
if [ "$FULL" = 1 ]; then
  python3 - "$MANIFEST" <<'PY'
import sys
m = open('AndroidManifest.xml').read()
block = open('manifest-full-service.xml').read().split('-->', 1)[1].lstrip('\n')
marker = '        <!-- FULL_ONLY: accessibility service is inserted here by build.sh FULL=1 -->\n'
assert marker in m
open(sys.argv[1], 'w').write(m.replace(marker, block))
PY
else
  cp AndroidManifest.xml "$MANIFEST"
fi

echo "== resources"
aapt package -f -m -J "$OUT/gen" -M "$MANIFEST" -S res -A assets -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$OUT/unaligned.apk"

echo "== java"
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -nowarn \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== dex"
"$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && zip -q -X ../unaligned.apk classes.dex)

echo "== align + sign"
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
apksigner sign --ks framecast.keystore --ks-pass pass:framecast --key-pass pass:framecast \
  --ks-key-alias framecast --min-sdk-version 26 --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
apksigner verify "$OUT/$APK_NAME"
ls -la "$OUT/$APK_NAME"
