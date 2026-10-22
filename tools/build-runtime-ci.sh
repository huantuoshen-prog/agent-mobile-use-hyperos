#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK required}}"
BT="$SDK/build-tools/${BUILD_TOOLS_VERSION:-34.0.0}"
JAR="$SDK/platforms/${PLATFORM:-android-28}/android.jar"
OUT="$ROOT/vd-tool-java/bin/classes"
mkdir -p "$OUT"
find "$OUT" -type f -name '*.class' -delete
javac -encoding UTF-8 -source 8 -target 8 -cp "$JAR" -d "$OUT" "$ROOT"/vd-tool-java/src/com/agent/*.java
for pair in 'ToolMain agent_tools' 'DaemonMain agent_vd'; do
    read -r cls output <<< "$pair"
    mkdir -p "$ROOT/vd-tool-java/bin/dex-output"
    "$BT/d8" --release --min-api 26 --lib "$JAR" --output "$ROOT/vd-tool-java/bin/dex-output" "$OUT"/com/agent/"$cls"*.class
    cp "$ROOT/vd-tool-java/bin/dex-output/classes.dex" "$ROOT/vd-tool-java/bin/$output.dex"
done
(cd "$ROOT/vd-server-go" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o vd_server .)
