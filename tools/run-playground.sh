#!/usr/bin/env bash
# Copyright 2026 Creative Moods, Lda.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
cd "$(dirname "$0")/.."
playground_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
playground_adb="$playground_sdk/platform-tools/adb"
playground_emulator="$playground_sdk/emulator/emulator"
playground_serial="${PLAYGROUND_SERIAL:-emulator-5554}"
if [[ -d "$PWD/.tools/jdk-17.0.21+9" ]]; then
    export JAVA_HOME="$PWD/.tools/jdk-17.0.21+9"
fi
./gradlew assembleDebug --console=plain
if ! "$playground_adb" devices | awk '{print $1}' | rg -qx "$playground_serial"; then
    playground_avd="${PLAYGROUND_AVD:-$("$playground_emulator" -list-avds | head -n 1)}"
    if [[ -z "$playground_avd" ]]; then
        echo "Create an Android API 29+ virtual phone in Android Studio Device Manager first." >&2
        exit 1
    fi
    playground_args=(-avd "$playground_avd" -no-snapshot -no-audio)
    if [[ ! -r /dev/kvm ]]; then
        echo "Starting without KVM; software emulation can take several minutes."
        playground_args+=(-accel off -gpu swiftshader_indirect)
    fi
    "$playground_emulator" "${playground_args[@]}" > /tmp/scorepuff-emulator.log 2>&1 &
fi
printf 'Waiting for %s to boot' "$playground_serial"
playground_booted=false
for ((attempt=0; attempt<180; attempt++)); do
    if [[ "$("$playground_adb" -s "$playground_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        playground_booted=true
        break
    fi
    printf '.'
    sleep 5
done
printf '\n'
if [[ "$playground_booted" != true ]]; then
    echo "Emulator did not finish booting. See /tmp/scorepuff-emulator.log or Android Studio Device Manager." >&2
    exit 1
fi
"$playground_adb" -s "$playground_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$playground_adb" -s "$playground_serial" shell am start -W -n pt.creativemoods.scorepuff/.ui.MockCatalogActivity
