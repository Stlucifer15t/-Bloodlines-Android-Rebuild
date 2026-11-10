#!/usr/bin/env bash
#
# apk-intake.sh — turn an Android APK into a small "intake bundle" that is enough to identify the
# runtime architecture, the graphics path and the touch-input plumbing WITHOUT shipping a large
# proprietary binary.
#
# Run it on your PC (not on the phone). Needs: bash, unzip, zip, sha256sum.
# Optional but strongly recommended: readelf + nm (binutils) — they produce the symbol evidence that
# decides what libd3d9_gles.so / libdxvk_d3d9*.so actually are.
#
# Usage:
#   tools/apk-intake.sh /path/to/base.apk                 # text bundle only (small, ~1-3 MB)
#   tools/apk-intake.sh /path/to/base.apk --with-libs      # also include lib/arm64-v8a/*.so
#   tools/apk-intake.sh /path/to/base.apk -o ~/intake      # output directory
#
# Produces: <outdir>/apk-intake-<sha8>.zip
#
set -uo pipefail

APK=""
WITH_LIBS=0
OUTDIR="."

while [ $# -gt 0 ]; do
    case "$1" in
        --with-libs) WITH_LIBS=1 ;;
        -o) shift; OUTDIR="${1:-.}" ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) APK="$1" ;;
    esac
    shift
done

[ -n "$APK" ] && [ -f "$APK" ] || { echo "usage: $0 /path/to/app.apk [--with-libs] [-o outdir]" >&2; exit 2; }

for tool in unzip zip sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SHA8="${SHA:0:8}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
B="$WORK/bundle"
mkdir -p "$B"
mkdir -p "$OUTDIR"

have() { command -v "$1" >/dev/null 2>&1; }

note() { printf '%s\n' "$*" | tee -a "$B/00-README.txt" >/dev/null; }
note "APK intake bundle"
note "================="
note "source apk : $(basename "$APK")"
note "sha256     : $SHA"
note "size bytes : $(wc -c < "$APK" | tr -d ' ')"
note "created    : $(date -Is)"
note "host tools : unzip=$(have unzip && echo yes) readelf=$(have readelf && echo yes) nm=$(have nm && echo yes) strings=$(have strings && echo yes) aapt2=$(have aapt2 && echo yes)"
note ""
note "Files in this bundle:"
note "  00-README.txt          this file"
note "  01-manifest-strings.txt  readable strings from AndroidManifest.xml (package, activities, features)"
note "  02-contents.txt        full APK entry listing (what is actually inside)"
note "  03-libs-*.txt          per-library: hash, size, ELF header, NEEDED/SONAME/RUNPATH, filtered symbols"
note "  04-assets-list.txt     asset listing (Wine/Box64/DXVK/driver payloads show up here)"
note "  05-asset-config/       small text/config assets copied verbatim (dxvk.conf, *.json, *.ini, box64 rc...)"
note "  06-strings-*.txt       runtime-relevant strings from the key native libraries"
note "  07-dex-strings.txt     runtime-relevant strings from classes*.dex"
note "  libs/                  native libraries (only with --with-libs)"
note ""
note "Nothing here is uploaded anywhere by this script; it only writes a local zip."

echo "[1/7] hashing and listing"
unzip -l "$APK" > "$B/02-contents.txt" 2>/dev/null || {
    python3 -m zipfile -l "$APK" > "$B/02-contents.txt" 2>/dev/null || echo "(could not list)" > "$B/02-contents.txt"
}

echo "[2/7] manifest"
unzip -p "$APK" AndroidManifest.xml > "$WORK/AndroidManifest.xml" 2>/dev/null || true
{
    echo "== strings from AndroidManifest.xml =="
    if have strings && [ -s "$WORK/AndroidManifest.xml" ]; then
        strings -n 3 "$WORK/AndroidManifest.xml" | head -400
    else
        echo "(no strings tool, or manifest missing)"
    fi
    echo
    echo "== package/activity hints from the listing =="
    grep -iE 'classes[0-9]*\.dex|resources\.arsc' "$B/02-contents.txt" || true
} > "$B/01-manifest-strings.txt"
if have aapt2; then
    aapt2 dump badging "$APK" >> "$B/01-manifest-strings.txt" 2>/dev/null || true
fi

echo "[3/7] native libraries"
mkdir -p "$WORK/libs"
unzip -o -j "$APK" 'lib/arm64-v8a/*.so' 'lib/armeabi-v7a/*.so' -d "$WORK/libs" >/dev/null 2>&1 || true
SYM_RE='vk_icd|vkCreateInstance|vkGetInstanceProcAddr|Vulkan|IDirect3D|Direct3DCreate9|D3DPERF|SDL_|SDLActivity|ANativeWindow|AHardwareBuffer|JNI_OnLoad|Java_|wine|ntdll|box64|dynarec|panfrost|panvk|kbase|turnip|gladio|vortek|zink|libGLES|eglGet|dlsym'
{
    echo "== lib/ contents =="
    grep -E 'lib/(arm64-v8a|armeabi-v7a)/' "$B/02-contents.txt" || echo "(none)"
    echo
    for lib in "$WORK"/libs/*; do
        [ -f "$lib" ] || continue
        name="$(basename "$lib")"
        echo
        echo "=================================================================="
        echo "== $name"
        echo "   size   : $(wc -c < "$lib" | tr -d ' ') bytes"
        echo "   sha256 : $(sha256sum "$lib" | cut -d' ' -f1)"
        if have readelf; then
            echo "-- ELF header --"
            readelf -h "$lib" 2>/dev/null | grep -E 'Class|Machine|Type|OS/ABI' || echo "(not an ELF)"
            echo "-- dynamic section (NEEDED / SONAME / RUNPATH) --"
            readelf -d "$lib" 2>/dev/null | grep -E 'NEEDED|SONAME|RPATH|RUNPATH|FLAGS' || echo "(none)"
            echo "-- exports (filtered) --"
            readelf -Ws --dyn-syms "$lib" 2>/dev/null | grep -iE "$SYM_RE" | awk '{print $8}' | sort -u | head -80 || true
        else
            echo "(readelf not installed: install binutils for symbol evidence)"
        fi
        if have nm; then
            echo "-- nm -D (filtered) --"
            nm -D --defined-only "$lib" 2>/dev/null | grep -iE "$SYM_RE" | head -80 || true
        fi
    done
} > "$B/03-libs-symbols.txt"

echo "[4/7] assets"
grep -iE '^\s+[0-9]+ .* assets/' "$B/02-contents.txt" > "$B/04-assets-list.txt" 2>/dev/null || true
if [ ! -s "$B/04-assets-list.txt" ]; then
    grep -iE 'assets/' "$B/02-contents.txt" > "$B/04-assets-list.txt" 2>/dev/null || echo "(no assets listed)" > "$B/04-assets-list.txt"
fi
{
    echo
    echo "== payload-looking entries (Wine / Box64 / DXVK / drivers) =="
    grep -iE 'wine|box64|box86|rootfs|\.tzst|\.wcp|\.adpkg|dxvk|turnip|panvk|gladio|vortek|graphics|driver|imagefs' "$B/02-contents.txt" | head -120 || echo "(none)"
} >> "$B/04-assets-list.txt"

echo "[5/7] small config assets"
mkdir -p "$B/05-asset-config"
count=0
while IFS= read -r entry; do
    case "$entry" in
        assets/*.conf|assets/*.cfg|assets/*.ini|assets/*.json|assets/*.txt|assets/*.rc|assets/*.sh|assets/*.toml|assets/*.xml)
            size="$(unzip -l "$APK" "$entry" 2>/dev/null | awk 'NR==4{print $1}')"
            if [ -n "${size:-}" ] && [ "$size" -lt 262144 ] 2>/dev/null; then
                mkdir -p "$B/05-asset-config/$(dirname "$entry")"
                unzip -p "$APK" "$entry" > "$B/05-asset-config/$entry" 2>/dev/null && count=$((count+1))
            fi
            ;;
    esac
done < <(grep -oE 'assets/[^ ]+' "$B/02-contents.txt" 2>/dev/null | sort -u)
note ""
note "config assets copied: $count"

echo "[6/7] targeted strings"
if have strings; then
    for lib in "$WORK"/libs/libmain.so "$WORK"/libs/libd3d9_gles.so "$WORK"/libs/libdxvk_d3d9.so "$WORK"/libs/libdxvk_d3d9_legacy.so "$WORK"/libs/libSDL3.so; do
        [ -f "$lib" ] || continue
        name="$(basename "$lib")"
        strings -n 6 "$lib" 2>/dev/null \
            | grep -iE 'vulkan|dxvk|box64|wine|SDL|panvk|panfrost|turnip|gladio|vortek|zink|X11|wayland|libGLES|robustness|textureCompression' \
            | sort -u | head -250 > "$B/06-strings-$name.txt"
    done
    {
        for dex in $(unzip -l "$APK" 2>/dev/null | grep -oE 'classes[0-9]*\.dex'); do
            unzip -p "$APK" "$dex" 2>/dev/null | strings -n 6 \
                | grep -iE 'winlator|box64|box86|wine|dxvk|SDL|GameActivity|input|hud|touch|joystick|vulkan|container' \
                | sort -u | head -200
        done
    } > "$B/07-dex-strings.txt" 2>/dev/null || true
else
    note "strings tool missing: skipped 06/07"
fi

echo "[7/7] packaging"
if [ "$WITH_LIBS" -eq 1 ]; then
    mkdir -p "$B/libs"
    cp "$WORK"/libs/*.so "$B/libs/" 2>/dev/null || true
    note ""
    note "libs/ included ($(ls -1 "$B/libs" 2>/dev/null | wc -l | tr -d ' ') files) — Tier B"
else
    note ""
    note "libs/ NOT included — Tier A (text evidence only)."
    note "If the architecture still cannot be settled from the symbols, re-run with --with-libs."
fi

ZIP="$OUTDIR/apk-intake-$SHA8.zip"
( cd "$WORK" && zip -qr "$ZIP" bundle ) || { echo "zip failed" >&2; exit 1; }

echo
echo "Bundle: $ZIP  ($(wc -c < "$ZIP" | tr -d ' ') bytes)"
echo "SHA-256 of apk: $SHA"
echo
echo "Next: hand this zip to the agent (attach it in the chat if under the upload limit, otherwise"
echo "put it in a GitHub repository and say which one)."
