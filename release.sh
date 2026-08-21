#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Use JDK 21 if it's installed at the common Homebrew path and JAVA_HOME isn't set.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/opt/homebrew/opt/openjdk@21" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi

./gradlew assembleDebug :overlay:assembleDebug

cp app/build/outputs/apk/debug/app-debug.apk ksu-module/system/app/Birdify/birdify.apk
cp overlay/build/outputs/apk/debug/overlay-debug.apk ksu-module/system/product/overlay/birdify-overlay.apk

(cd ksu-module && rm -f ../birdify-ksu.zip && zip -r -q ../birdify-ksu.zip module.prop system/)

echo "Built birdify-ksu.zip"
