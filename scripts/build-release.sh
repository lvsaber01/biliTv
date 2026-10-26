#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# Reuse the local Android build toolchain when callers have not configured one.
# Keep explicit JAVA_HOME / Android SDK environment variables authoritative.
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME=$candidate
            export JAVA_HOME
            break
        fi
    done
fi

if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi

if ! java -version >/dev/null 2>&1; then
    echo "A usable Java runtime is required; set JAVA_HOME to JDK 17 or newer." >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] \
    && [ -d "$PROJECT_ROOT/.toolcache/android" ]; then
    ANDROID_HOME="$PROJECT_ROOT/.toolcache/android"
    export ANDROID_HOME
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Android SDK not found; set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
fi

RELEASE_SIGNING_DIR="$HOME/Library/Application Support/biliTv/signing"
RELEASE_KEYSTORE_PATH="$RELEASE_SIGNING_DIR/biliTv-release.p12"
RELEASE_PASSWORD_FILE="$RELEASE_SIGNING_DIR/release-password.txt"

if [ ! -f "$RELEASE_KEYSTORE_PATH" ] || [ ! -f "$RELEASE_PASSWORD_FILE" ]; then
    echo "Local Release signing materials were not found." >&2
    exit 1
fi

export BILITV_RELEASE_STORE_FILE="$RELEASE_KEYSTORE_PATH"
export BILITV_RELEASE_PASSWORD_FILE="$RELEASE_PASSWORD_FILE"
export BILITV_RELEASE_KEY_ALIAS=bilitv-release

cd "$PROJECT_ROOT"
./gradlew assembleRelease
