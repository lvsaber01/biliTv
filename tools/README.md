# Android device labs

Choose the target profile explicitly for every test run. Each profile has a separate AVD data directory and emulator port; the selector does not start or stop other profiles.

| Profile | AVD | Android image | Display | ADB serial |
| --- | --- | --- | --- | --- |
| `tv` | `biliTv_TV_API31_ARM64` | Android TV API 31, ARM64 | 3840×2160 (4K) panel; 1920×1080 logical pixels at 160dpi (≈1920×1080dp) | `emulator-5580` |
| `tablet` | `biliTv_Tablet_API35_ARM64` | Google APIs API 35, ARM64 | Pixel Tablet, 2560×1600, landscape | `emulator-5584` |
| `phone` | `biliTv_Phone_API35_ARM64` | Android API 35, ARM64 | Pixel 2, 1080×1920, portrait | `emulator-5570` |

The TV profile overrides the SDK's 1080p base device to a 3840×2160 physical canvas. Android TV presents a 1920×1080 logical mode, so the lab fixes that logical mode at 160dpi to model the same 1920×1080dp layout canvas expected from a 4K/320dpi output. The tablet profile uses the already-installed Google APIs API 35 ARM64 image, so it does not require another system-image download or license acceptance on this workstation. AVD files, emulator data, screenshots and logs stay under `.toolcache/`, which is excluded from Git.

## Common commands

```bash
# First-time profile setup; safe to repeat
bash tools/device-lab.sh tablet prepare

# Build the debug APK, start the selected emulator, install and open biliTv
bash tools/device-lab.sh tablet launch

# The same flow for TV or phone
bash tools/device-lab.sh tv launch
bash tools/device-lab.sh phone launch

# Capture a screenshot and app-process diagnostics, then stop only that profile
bash tools/device-lab.sh tablet capture
bash tools/device-lab.sh tablet stop
```

Use `start` to open the emulator without building/installing the app, then use `wait`, `install`, `run`, `status`, `capture`, or `stop` as needed. `key`, `tap`, and `swipe` are available for UI automation. The underlying profile scripts accept the same commands directly, for example `bash tools/tablet-lab.sh launch`.

Do not use an unqualified `adb` command when multiple emulators or physical devices may be connected. The profile scripts target their own fixed serial and verify the AVD name before installing, reading logs, or stopping it.

These emulators validate Android UI and general app behavior; they do not model Shield hardware decoding, HDR, vendor firmware, or physical remote quirks. Keep TV navigation tests and tablet touch/layout tests as separate acceptance passes.
