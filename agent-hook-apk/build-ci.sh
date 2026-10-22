#!/bin/bash
# Portable build for CI (GitHub Actions) and any Linux box with an Android SDK.
#
# The upstream build.sh hard-codes Debian-packaged aapt/dx/apksigner paths
# (/usr/lib/android-sdk, /root/xposed_lib). This script instead uses
# ANDROID_SDK_ROOT/ANDROID_HOME and d8 (dx was removed from modern build-tools).
# Compile-time Xposed API stubs live in ./stubs and are NOT packaged into the APK.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    echo "[build-ci] ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
    exit 1
fi

PLATFORM="${PLATFORM:-android-28}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"

for f in "$JAR" "$BT/aapt" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    if [ ! -e "$f" ]; then
        echo "[build-ci] missing required tool: $f" >&2
        exit 1
    fi
done

BUILD="$SCRIPT_DIR/build-ci"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/stubs_classes" "$BUILD/apk" "$BUILD/dex"

echo "[build-ci] aapt package (resources + R.java)"
"$BT/aapt" package -m -0 arsc -S "$SCRIPT_DIR/res" -J "$BUILD/gen" \
    -M "$SCRIPT_DIR/AndroidManifest.xml" -I "$JAR" -F "$BUILD/apk/unaligned.apk"

echo "[build-ci] compile Xposed API stubs (compile-time only)"
if [ -d "$SCRIPT_DIR/stubs" ]; then
    find "$SCRIPT_DIR/stubs" -name '*.java' > "$BUILD/stub-sources.txt"
    javac -proc:none -source 8 -target 8 -nowarn -encoding UTF-8 \
        -cp "$JAR" -d "$BUILD/stubs_classes" @"$BUILD/stub-sources.txt"
fi

echo "[build-ci] compile module sources"
find "$SCRIPT_DIR/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -proc:none -source 8 -target 8 -nowarn -encoding UTF-8 \
    -cp "$JAR:$BUILD/stubs_classes" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[build-ci] d8 -> classes.dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/class-files.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$BUILD/dex" @"$BUILD/class-files.txt"
cp "$BUILD/dex/classes.dex" "$BUILD/apk/classes.dex"
( cd "$BUILD/apk" && "$BT/aapt" add unaligned.apk classes.dex )
rm -f "$BUILD/apk/classes.dex"

echo "[build-ci] add assets"
if [ -d "$SCRIPT_DIR/assets" ]; then
    while IFS= read -r asset; do
        rel="${asset#"$SCRIPT_DIR"/}"
        ( cd "$SCRIPT_DIR" && "$BT/aapt" add "$BUILD/apk/unaligned.apk" "$rel" )
    done < <(find "$SCRIPT_DIR/assets" -type f)
fi

echo "[build-ci] zipalign"
"$BT/zipalign" -p -f 4 "$BUILD/apk/unaligned.apk" "$BUILD/apk/aligned.apk"

echo "[build-ci] keystore + sign"
mkdir -p "$SCRIPT_DIR/build"
if [ "${BUILD_UNSIGNED:-0}" = 1 ]; then
    cp "$BUILD/apk/aligned.apk" "$SCRIPT_DIR/build/agent_hook-unsigned.apk"
    echo "[build-ci] unsigned compilation artifact; not an installable release"
    exit 0
fi
KEYSTORE="${KEYSTORE:?Provide the persistent signing keystore outside build-ci}"
[ -f "$KEYSTORE" ] || { echo '[build-ci] persistent signing key missing' >&2; exit 1; }
: "${KEYSTORE_PASSWORD:?Provide signing password through environment}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD \
    --out "$SCRIPT_DIR/build/agent_hook.apk" "$BUILD/apk/aligned.apk"
"$BT/apksigner" verify "$SCRIPT_DIR/build/agent_hook.apk"

echo "[build-ci] OK: $SCRIPT_DIR/build/agent_hook.apk"
