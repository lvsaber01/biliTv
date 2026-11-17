#!/usr/bin/env bash
# Reusable local Android tablet lab; AVD state, APKs and captures stay outside Git.
set -euo pipefail
umask 077

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${BILITV_SDK_ROOT:-$PROJECT_ROOT/.toolcache/android}"
GRADLE_BIN="${BILITV_GRADLE_BIN:-$PROJECT_ROOT/.toolcache/gradle/gradle-8.10.2/bin/gradle}"
LAB_ROOT="$PROJECT_ROOT/.toolcache/tablet-lab"
AVD_NAME="biliTv_Tablet_API35_ARM64"
IMAGE="system-images;android-35;google_apis;arm64-v8a"
PORT=5584
SERIAL="emulator-$PORT"
APP="com.lvsaber.bilitv.debug"
ADB="$SDK_ROOT/platform-tools/adb"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_AVD_HOME="$LAB_ROOT/avd"
if [[ -z "${JAVA_HOME:-}" && -x "$PROJECT_ROOT/.toolcache/jdk/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="$PROJECT_ROOT/.toolcache/jdk/Contents/Home"
elif [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
mkdir -p "$ANDROID_AVD_HOME" "$LAB_ROOT/captures"
chmod 700 "$LAB_ROOT"

die() { echo "$*" >&2; exit 1; }
device() { "$ADB" -s "$SERIAL" "$@"; }
require_device() {
    [[ -x "$ADB" ]] || die "ADB missing: install the Android SDK tools first."
    [[ "$(device get-state 2>/dev/null || true)" == device ]] || die "Start the lab first: bash tools/tablet-lab.sh start"
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
        [[ $count -lt 240 ]] || die "Boot timed out; inspect $LAB_ROOT/emulator.log"
        sleep 1
    done
    require_device
}
latest_apk() {
    rg --files --no-ignore "$PROJECT_ROOT/app/build/outputs/apk/debug" 2>/dev/null \
        | rg '/biliTv-debug-v[^/]+\.apk$' | sort -V | tail -n 1
}

case "${1:-help}" in
    prepare)
        [[ "$(uname -m)" == arm64 ]] || die "This profile requires an Apple Silicon/ARM64 host."
        [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] || die "Android command-line tools missing under $SDK_ROOT"
        [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" ]] || die "avdmanager missing under $SDK_ROOT"
        [[ -x "$SDK_ROOT/emulator/emulator" ]] || die "Android Emulator missing under $SDK_ROOT"
        image_path="$SDK_ROOT/system-images/$(printf '%s' "${IMAGE#system-images;}" | tr ';' '/')"
        if [[ ! -f "$image_path/package.xml" ]]; then
            "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" "$IMAGE"
        fi
        if [[ ! -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]]; then
            "$SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd --name "$AVD_NAME" \
                --package "$IMAGE" --device pixel_tablet --path "$ANDROID_AVD_HOME/$AVD_NAME.avd"
        fi
        # The app's tablet validation targets the common landscape layout; touch remains enabled.
        perl -pi -e 's/^hw.initialOrientation=.*/hw.initialOrientation=landscape/' \
            "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
        "$SDK_ROOT/emulator/emulator" -accel-check
        ;;
    start)
        [[ -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]] || die "Run bash tools/tablet-lab.sh prepare first."
        if [[ "$(device get-state 2>/dev/null || true)" == device ]]; then
            require_device; echo "Already running: $SERIAL"; exit 0
        fi
        extra=(-no-boot-anim -no-snapshot)
        case "${2:-}" in
            --headless) extra=(-no-boot-anim -no-snapshot -no-window -no-audio) ;;
            "") ;;
            *) die "Usage: bash tools/tablet-lab.sh start [--headless]" ;;
        esac
        # Foreground by design: Ctrl-C closes this lab, never another device.
        exec "$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -port "$PORT" \
            -memory 4096 -cores 4 -gpu auto -feature -Vulkan \
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
        bash "$PROJECT_ROOT/tools/tablet-lab.sh" build
        launched_pid=""
        state="$(device get-state 2>/dev/null || true)"
        if [[ "$state" != device && "$state" != offline ]]; then
            nohup bash "$PROJECT_ROOT/tools/tablet-lab.sh" start > "$LAB_ROOT/emulator.log" 2>&1 < /dev/null &
            launched_pid=$!
        fi
        wait_ready "$launched_pid"
        bash "$PROJECT_ROOT/tools/tablet-lab.sh" install
        bash "$PROJECT_ROOT/tools/tablet-lab.sh" run
        echo 'Tablet lab ready. Use touch interaction or tools/tablet-lab.sh tap/swipe/key.'
        ;;
    install)
        require_device; wait_ready
        apk="${2:-$(latest_apk)}"
        [[ -f "$apk" ]] || die "APK missing; run bash tools/tablet-lab.sh build first."
        device install -r "$apk"
        ;;
    run)
        require_device; wait_ready
        device shell pm path "$APP" | /usr/bin/grep -q '^package:' || die "biliTv Debug is not installed. Run tools/tablet-lab.sh install."
        device shell am force-stop "$APP"
        device shell am start -W -n "$APP/com.lvsaber.bilitv.ui.MainActivity"
        activity="$(device shell dumpsys activity activities | /usr/bin/grep -E 'mResumedActivity|topResumedActivity' || true)"
        [[ "$activity" == *"com.lvsaber.bilitv.ui.MainActivity"* ]] || die "MainActivity did not open: $activity"
        ;;
    login)
        require_device; wait_ready
        device shell am start -W -n "$APP/com.lvsaber.bilitv.ui.MainActivity"
        activity="$(device shell dumpsys activity activities | /usr/bin/grep -E 'mResumedActivity|topResumedActivity' || true)"
        [[ "$activity" == *"com.lvsaber.bilitv.ui.MainActivity"* ]] || die "MainActivity did not open: $activity"
        echo 'In biliTv, open 我的 > 二维码登录. LoginActivity is not exported and must be opened through the app.'
        ;;
    key)
        require_device
        shift
        [[ $# -gt 0 ]] || die "Usage: bash tools/tablet-lab.sh key back home enter tab"
        for key in "$@"; do
            case "$key" in
                back) code=4 ;; home) code=3 ;; enter) code=66 ;; tab) code=61 ;;
                *) die "Unknown key: $key" ;;
            esac
            device shell input keyevent "$code"
        done
        ;;
    tap)
        require_device
        [[ $# == 3 && "$2" =~ ^[0-9]+$ && "$3" =~ ^[0-9]+$ ]] || die "Usage: bash tools/tablet-lab.sh tap X Y"
        device shell input tap "$2" "$3"
        ;;
    swipe)
        require_device
        [[ $# -ge 5 && "$2" =~ ^[0-9]+$ && "$3" =~ ^[0-9]+$ && "$4" =~ ^[0-9]+$ && "$5" =~ ^[0-9]+$ ]] \
            || die "Usage: bash tools/tablet-lab.sh swipe X1 Y1 X2 Y2 [DURATION_MS]"
        duration="${6:-350}"
        [[ "$duration" =~ ^[0-9]+$ ]] || die "Duration must be milliseconds."
        device shell input swipe "$2" "$3" "$4" "$5" "$duration"
        ;;
    capture)
        require_device
        stamp="$(date +%Y%m%d-%H%M%S)-$$"
        destination="$LAB_ROOT/captures/$stamp"
        mkdir -p "$destination"
        device exec-out screencap -p > "$destination/screen.png"
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
        device shell pm list features | /usr/bin/grep -E 'leanback|television|touchscreen' || true
        device shell wm size
        device shell wm density
        ;;
    stop)
        require_device
        device emu kill
        ;;
    *)
        echo 'Usage: bash tools/tablet-lab.sh prepare|launch|start [--headless]|wait|build|install [apk]|run|login|tap X Y|swipe X1 Y1 X2 Y2 [MS]|key ...|capture|logs|status|stop'
        ;;
esac
