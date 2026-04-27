#!/usr/bin/env bash
# Run lint + spotless. Use --fix to apply formatting.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ "${1:-}" == "--fix" ]]; then
    ./gradlew :manager:spotlessApply :manager:lint
else
    ./gradlew :manager:spotlessCheck :manager:lint
fi
