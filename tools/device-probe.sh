#!/usr/bin/env bash
#
# device-probe.sh — collect the evidence needed to finish Assignment 001 on a real device.
#
# Answers, without root:
#   D1/D2  is the port a Wine/Box64 container or a native arm64 app?
#   D3/D4  which Vulkan/GLES libraries does the running game actually map?
#   D5     what does libd3d9_gles.so / libdxvk_d3d9*.so actually do?
#   D6     can a custom Vulkan ICD (PanVK) even be loaded by this runtime?
#   D7     kbase / Mali feasibility basics for PanVK.
#
# Usage:
#   tools/device-probe.sh                       # auto-detect the Bloodlines package
#   tools/device-probe.sh -p com.example.game   # explicit package
#   tools/device-probe.sh -p com.example.game -a /path/to/base.apk
#   tools/device-probe.sh -p com.example.game -t 10    # also capture an atrace for 10s
#
# Output: tools/../probe-out/<timestamp>/report.txt (+ raw files) and .tar.gz
#
set -uo pipefail

PKG=""
APK=""
TRACE_SECONDS=""
OUT_ROOT="$(cd "$(dirname "$0")/.." && pwd)/probe-out"

while getopts "p:a:t:o:h" opt; do
    case "$opt" in
        p) PKG="$OPTARG" ;;
        a) APK="$OPTARG" ;;
        t) TRACE_SECONDS="$OPTARG" ;;
        o) OUT_ROOT="$OPTARG" ;;
        h) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "unknown option; use -h" >&2; exit 2 ;;
    esac
done

command -v adb >/dev/null 2>&1 || { echo "adb not found in PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device: check 'adb devices' and USB debugging" >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_ROOT/$STAMP"
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
: > "$REPORT"

say() { printf '%s\n' "$*" | tee -a "$REPORT"; }
section() { printf '\n=== %s ===\n' "$*" | tee -a "$REPORT"; }
# run <file-name> <shell command...> — records output, never aborts the probe
run() {
    local name="$1"; shift
    {
        printf '\n--- %s ---\n' "$name"
        adb shell "$@" 2>&1
    } | tee -a "$REPORT" | tee "$OUT/$name.txt" >/dev/null
}

if [ -z "$PKG" ]; then
    PKG="$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' \
        | grep -iE 'blood|vampire|vtmb' | head -1)"
fi

say "Bloodlines Android Rebuild — device probe"
say "date    : $(date -Is)"
say "device  : $(adb shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r') $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
say "package : ${PKG:-<not detected>}"
say "android : $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') (sdk $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"
say "soc     : $(adb shell getprop ro.board.platform 2>/dev/null | tr -d '\r') / $(adb shell getprop ro.hardware 2>/dev/null | tr -d '\r')"

section "1. Platform and CPU"
run abi          getprop ro.product.cpu.abilist
run soc_detail   getprop ro.soc.model
run cpuinfo      cat /proc/cpuinfo
run meminfo      cat /proc/meminfo

section "2. GPU / display"
run display      dumpsys display
adb shell dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'GLES|Vulkan|GPU|driver' | head -30 \
    | tee -a "$REPORT" > "$OUT/surfaceflinger-gl.txt"
run vulkan_hal   sh -c 'ls -l /vendor/lib64/hw/ /vendor/lib/hw/ 2>/dev/null | grep -i vulkan'
run egl_libs     sh -c 'ls -l /vendor/lib64/egl/ /vendor/lib/egl/ 2>/dev/null'
run mali_dev     sh -c 'ls -l /dev/mali0 2>&1'
run kbase_ver    sh -c 'cat /sys/kernel/debug/mali0/version 2>&1 || echo "(needs root; use PANVK_DEBUG=startup instead)"'
run kbase_uapi   sh -c 'grep -i kbase /proc/version 2>&1; uname -a'

section "3. The APK itself (D1/D2/D5)"
if [ -z "$APK" ] && [ -n "$PKG" ]; then
    APKPATH="$(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -1)"
    if [ -n "${APKPATH:-}" ]; then
        say "pulling $APKPATH"
        adb pull "$APKPATH" "$OUT/base.apk" >/dev/null 2>&1 && APK="$OUT/base.apk"
    fi
fi
if [ -n "$APK" ] && [ -f "$APK" ]; then
    unzip -l "$APK" 2>/dev/null > "$OUT/apk-contents.txt"
    say "APK: $(basename "$APK")  ($(wc -l < "$OUT/apk-contents.txt") entries)"
    say "--- lib/arm64-v8a ---"
    grep -E 'lib/arm64-v8a/' "$OUT/apk-contents.txt" | head -60 | tee -a "$REPORT"
    say "--- container / driver assets ---"
    grep -iE 'assets/.*(wine|box64|box86|rootfs|\.tzst|\.wcp|dxvk|graphics_driver|turnip|panvk)' \
        "$OUT/apk-contents.txt" | head -40 | tee -a "$REPORT"
    # Extract native libs for symbol inspection
    mkdir -p "$OUT/libs"
    unzip -o -j "$APK" 'lib/arm64-v8a/*.so' -d "$OUT/libs" >/dev/null 2>&1
    if command -v llvm-nm >/dev/null 2>&1; then
        NM=llvm-nm
    elif command -v nm >/dev/null 2>&1; then
        NM=nm
    else
        NM=""
    fi
    if [ -n "$NM" ]; then
        for lib in "$OUT"/libs/libd3d9_gles.so "$OUT"/libs/libdxvk_d3d9.so \
                   "$OUT"/libs/libdxvk_d3d9_legacy.so "$OUT"/libs/libvulkan*.so; do
            [ -f "$lib" ] || continue
            base="$(basename "$lib")"
            "$NM" -D --defined-only "$lib" 2>/dev/null > "$OUT/symbols-$base.txt"
            say "--- symbols: $base (first 25 interesting) ---"
            grep -iE 'vk_icd|vkCreateInstance|IDirect3D|Direct3DCreate9|SDL_|ANativeWindow|Vulkan' \
                "$OUT/symbols-$base.txt" | head -25 | tee -a "$REPORT"
        done
    else
        say "(no nm/llvm-nm available: install binutils or LLVM to dump symbols)"
    fi
else
    say "No APK available; skipping container/symbol analysis (pass -a /path/to/base.apk)."
fi

section "4. Runtime evidence while the game is running (D3/D4/D6)"
PID=""
if [ -n "$PKG" ]; then
    PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
fi
if [ -n "${PID:-}" ]; then
    say "pid = $PID"
    adb shell "cat /proc/$PID/maps" 2>/dev/null | grep -iE 'vulkan|GLES|dxvk|d3d9|wine|box64|SDL|panfrost' \
        | tee -a "$REPORT" > "$OUT/maps-vulkan.txt"
    say "--- process tree / emulation check ---"
    adb shell 'ps -A -o PID,PPID,NAME' 2>/dev/null | grep -iE 'wine|box64|box86|vampire|wineserver' \
        | tee -a "$REPORT"
    say "--- threads ---"
    adb shell "ls /proc/$PID/task" 2>/dev/null | wc -l | tee -a "$REPORT"
    say "--- env of the process (may be empty without root) ---"
    adb shell "cat /proc/$PID/environ" 2>/dev/null | tr '\0' '\n' \
        | grep -iE 'VK_|MESA|DXVK|BOX64|WINE|PANVK|GALLIUM' | tee -a "$REPORT"
    adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" 2>/dev/null
    say "gfxinfo saved (frame pacing histogram if the app uses HWUI; ignored for native renderers)"
    if [ -n "$TRACE_SECONDS" ]; then
        say "capturing atrace for ${TRACE_SECONDS}s ..."
        adb shell "atrace -t $TRACE_SECONDS -b 8192 gfx view sched freq idle am -a $PKG" \
            > "$OUT/trace.atrace" 2>/dev/null
        say "trace saved: trace.atrace ($(wc -c < "$OUT/trace.atrace" 2>/dev/null || echo 0) bytes)"
    fi
else
    say "Game not running (or package unknown). Start the game, then re-run this script for D3/D4/D6."
fi

section "5. Audio (A1/A2 inputs)"
run audio_flinger sh -c 'dumpsys media.audio_flinger 2>/dev/null | head -60'
run audio_policy  getprop | grep -iE 'audio|sample'

section "6. Interpretation"
{
    echo
    echo "Check these against docs/reports/001-technical-investigation.md:"
    echo "  D1/D2  Wine/Box64 assets or libbox64/libwine in the APK  -> Hypothesis A (container)"
    echo "         only engine libs (libmain/libSDL3/libdxvk_d3d9*)  -> Hypothesis B (native port)"
    echo "  D3     a wineserver/vampire.exe process tree            -> Hypothesis A"
    echo "  D4     maps shows /system/lib64/libvulkan.so            -> system ICD only (PanVK needs a loader shim)"
    echo "         maps shows an app-private libvulkan.so.1        -> custom ICD is loadable (PanVK viable)"
    echo "  D5     libd3d9_gles.so exports IDirect3D9*              -> it is a real D3D9->GLES front end"
    echo "         (no IDirect3D symbols)                          -> it is a presentation proxy instead"
    echo "  D7     /dev/mali0 present + kbase 11.x                  -> PanVK-on-kbase is plausible"
} | tee -a "$REPORT"

tar -czf "$OUT.tar.gz" -C "$(dirname "$OUT")" "$(basename "$OUT")" 2>/dev/null
echo
echo "Report : $REPORT"
echo "Archive: $OUT.tar.gz"
