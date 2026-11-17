#!/usr/bin/env bash
# Reusable local TV lab; all images, device data and captures stay outside Git.
set -euo pipefail
umask 077

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${BILITV_SDK_ROOT:-$PROJECT_ROOT/.toolcache/android}"
GRADLE_BIN="${BILITV_GRADLE_BIN:-$PROJECT_ROOT/.toolcache/gradle/gradle-8.10.2/bin/gradle}"
LAB_ROOT="$PROJECT_ROOT/.toolcache/tv-lab"
AVD_NAME="biliTv_TV_API31_ARM64"
IMAGE="system-images;android-31;android-tv;arm64-v8a"
PORT=5580
SERIAL="emulator-$PORT"
TV_WIDTH=3840
TV_HEIGHT=2160
TV_DENSITY=320
TV_LOGICAL_WIDTH=1920
TV_LOGICAL_HEIGHT=1080
TV_LOGICAL_DENSITY=160
APP="com.lvsaber.bilitv.debug"
ADB="$SDK_ROOT/platform-tools/adb"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_AVD_HOME="$LAB_ROOT/avd"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${JAVA_HOME:-}" && -x "$PROJECT_ROOT/.toolcache/jdk/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="$PROJECT_ROOT/.toolcache/jdk/Contents/Home"
fi
mkdir -p "$ANDROID_AVD_HOME" "$LAB_ROOT/captures"
chmod 700 "$LAB_ROOT"

die() { echo "$*" >&2; exit 1; }
device() { "$ADB" -s "$SERIAL" "$@"; }
require_device() {
    [[ -x "$ADB" ]] || die "ADB missing: install the Android SDK tools first."
    [[ "$(device get-state 2>/dev/null || true)" == device ]] || die "Start the lab first: bash tools/tv-lab.sh start"
    local actual
    actual="$(device emu avd name | tr -d '\r' | head -n 1)"
    [[ "$actual" == "$AVD_NAME" ]] || die "Port $PORT belongs to another emulator ($actual); refusing to modify it."
}
wait_ready() {
    local count=0
    local launched_pid="${1:-}"
    until [[ "$(device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
        if [[ -n "$launched_pid" ]] && ! kill -0 "$launched_pid" 2>/dev/null; then
            die "Emulator exited before boot; inspect $LAB_ROOT/emulator.log"
        fi
        ((count+=1))
        [[ $count -lt 180 ]] || die "Boot timed out; inspect $LAB_ROOT/emulator.log"
        sleep 1
    done
    require_device
    # Android TV's 4K panel reports a 1080p logical mode. Model the 4K dp canvas
    # explicitly: 1920x1080 logical pixels at 160dpi, scaled to a 3840x2160 panel.
    device shell wm size "${TV_LOGICAL_WIDTH}x${TV_LOGICAL_HEIGHT}" >/dev/null
    device shell wm density "$TV_LOGICAL_DENSITY" >/dev/null
}

case "${1:-help}" in
    prepare)
        [[ "$(uname -m)" == arm64 ]] || die "This profile requires an Apple Silicon/ARM64 host."
        [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] || die "Android command-line tools missing under $SDK_ROOT"
        if [[ ! -x "$SDK_ROOT/emulator/emulator" ]]; then
            "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" emulator
        fi
        if [[ ! -f "$SDK_ROOT/system-images/android-31/android-tv/arm64-v8a/package.xml" ]]; then
            "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" "$IMAGE"
        fi
        if [[ ! -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]]; then
            "$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --name "$AVD_NAME" \
                --package "$IMAGE" --device tv_1080p --path "$ANDROID_AVD_HOME/$AVD_NAME.avd"
        fi
        # Use a 4K pixel canvas at a TV-like density. This yields 1920x1080dp,
        # unlike the SDK's 1080p profile at 320dpi (960x540dp).
        perl -pi -e "s/^hw.initialOrientation\\s*=.*/hw.initialOrientation = landscape/; s/^hw.keyboard\\s*=.*/hw.keyboard = yes/; s/^hw.screen\\s*=.*/hw.screen = no-touch/; s/^hw.lcd.width\\s*=.*/hw.lcd.width = ${TV_WIDTH}/; s/^hw.lcd.height\\s*=.*/hw.lcd.height = ${TV_HEIGHT}/; s/^hw.lcd.density\\s*=.*/hw.lcd.density = ${TV_DENSITY}/" \
            "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
        "$SDK_ROOT/emulator/emulator" -accel-check
        ;;
    start)
        [[ -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]] || die "Run bash tools/tv-lab.sh prepare first."
        if [[ "$(device get-state 2>/dev/null || true)" == device ]]; then
            require_device; echo "Already running: $SERIAL"; exit 0
        fi
        extra=(-no-boot-anim -no-snapshot)
        case "${2:-}" in
            --headless) extra=(-no-boot-anim -no-snapshot -no-window -no-audio) ;;
            "") ;;
            *) die "Usage: bash tools/tv-lab.sh start [--headless]" ;;
        esac
        # Foreground by design: Ctrl-C closes this lab, never another device.
        exec "$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -port "$PORT" \
            -memory 2048 -cores 2 -dpi-device "$TV_DENSITY" -gpu auto -feature -Vulkan \
            -no-metrics -crash-report-mode disabled "${extra[@]}"
        ;;
    wait) wait_ready ;;
    build)
        cd "$PROJECT_ROOT"
        [[ -x "${JAVA_HOME:-}/bin/java" ]] || die "Java runtime missing; set JAVA_HOME or install the project's local JDK."
        [[ -x "$GRADLE_BIN" ]] || die "Local Gradle missing: $GRADLE_BIN"
        export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PROJECT_ROOT/.toolcache/gradle-user}"
        "$GRADLE_BIN" --no-daemon --offline :app:assembleDebug
        ;;
    launch)
        bash "$PROJECT_ROOT/tools/tv-lab.sh" build
        launched_pid=""
        state="$(device get-state 2>/dev/null || true)"
        if [[ "$state" != device && "$state" != offline ]]; then
            nohup bash "$PROJECT_ROOT/tools/tv-lab.sh" start > "$LAB_ROOT/emulator.log" 2>&1 < /dev/null &
            launched_pid=$!
        fi
        wait_ready "$launched_pid"
        bash "$PROJECT_ROOT/tools/tv-lab.sh" install
        bash "$PROJECT_ROOT/tools/tv-lab.sh" run
        echo 'TV lab ready. Use arrow keys / Enter in the emulator, or tools/tv-lab.sh key.'
        ;;
    install)
        require_device; wait_ready
        apk="${2:-}"
        if [[ -z "$apk" ]]; then
            apk="$(rg --files --no-ignore "$PROJECT_ROOT/app/build/outputs/apk/debug" 2>/dev/null | rg '/biliTv-debug-v[^/]+\.apk$' | sort -V | tail -n 1)"
        fi
        [[ -f "$apk" ]] || die "APK missing; run bash tools/tv-lab.sh build first."
        device install -r "$apk"
        ;;
    run)
        require_device; wait_ready
        device shell pm path "$APP" | /usr/bin/grep -q '^package:' || die "biliTv is not installed. Run bash tools/tv-lab.sh install."
        device shell am force-stop "$APP"
        device shell am start -W -n "$APP/com.lvsaber.bilitv.ui.MainActivity"
        activity="$(device shell dumpsys activity activities | /usr/bin/grep -E 'mResumedActivity|topResumedActivity' || true)"
        [[ "$activity" == *"com.lvsaber.bilitv.ui.MainActivity"* ]] || die "MainActivity did not open: $activity"
        ;;
    key)
        require_device
        shift
        [[ $# -gt 0 ]] || die "Usage: bash tools/tv-lab.sh key up down left right ok back home menu playpause"
        for key in "$@"; do
            case "$key" in
                up) code=19 ;; down) code=20 ;; left) code=21 ;; right) code=22 ;;
                ok) code=23 ;; back) code=4 ;; home) code=3 ;; menu) code=82 ;; playpause) code=85 ;;
                *) die "Unknown key: $key" ;;
            esac
            device shell input keyevent "$code"
        done
        ;;
    capture)
        require_device
        stamp="$(date +%Y%m%d-%H%M%S)-$$"
        destination="$LAB_ROOT/captures/$stamp"
        mkdir -p "$destination"
        device exec-out screencap -p > "$destination/screen.png"
        # Process-scoped logs only; never export app-private data or account cookies.
        app_pid="$(device shell pidof "$APP" | tr -d '\r' || true)"
        if [[ -n "$app_pid" ]]; then device logcat -d --pid="${app_pid%% *}" -t 1500 > "$destination/app-logcat.txt"; fi
        { device shell getprop ro.build.version.release; device shell getprop ro.build.version.sdk;
          device shell getprop ro.product.cpu.abilist; device shell wm size; device shell wm density; } > "$destination/device.txt"
        device shell dumpsys activity activities | /usr/bin/grep -E 'mResumedActivity|topResumedActivity' > "$destination/activity.txt" || true
        echo "$destination"
        ;;
    logs)
        require_device
        app_pid="$(device shell pidof "$APP" | tr -d '\r')"
        [[ -n "$app_pid" ]] || die "Launch biliTv first."
        exec "$ADB" -s "$SERIAL" logcat --pid="${app_pid%% *}"
        ;;
    status)
        require_device
        device shell getprop ro.build.version.release
        device shell getprop ro.build.version.sdk
        device shell pm list features | /usr/bin/grep -E 'leanback|television|touchscreen'
        device shell wm size
        device shell wm density
        ;;
    stop)
        require_device
        device emu kill
        ;;
    *)
        echo 'Usage: bash tools/tv-lab.sh prepare|launch|start [--headless]|wait|build|install [apk]|run|key ...|capture|logs|status|stop'
        ;;
esac
