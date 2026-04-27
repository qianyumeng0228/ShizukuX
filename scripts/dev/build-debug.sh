#!/usr/bin/env bash
# Fast debug build for AI agents and developers. No Sentry upload.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew :manager:assembleDebug "$@"
echo "APK: $(find manager/build/outputs/apk/debug -name '*.apk' | head -1)"
