#!/usr/bin/env bash
# Convenience build: prefers a user-local Temurin JDK if present.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$HOME"/.jdks/jdk-17* "$HOME"/.jdks/jdk-21* /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64; do
    if [[ -x "$candidate/bin/jlink" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/jlink" ]]; then
  echo "Need a full JDK 17+ with jlink. Install Temurin or openjdk-*-jdk and set JAVA_HOME." >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JAVA_HOME=$JAVA_HOME"
./gradlew :app:assembleDebug "$@"
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
