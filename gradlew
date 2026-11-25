#!/bin/sh
# Lightweight local helper. CI uses the Gradle 8.9 distribution from gradle/gradle-build-action.
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle --no-daemon "$@"
fi
echo "Gradle 8.9 is not installed. Install Gradle 8.9, then run: gradle --no-daemon clean assembleDebug" >&2
exit 1
