# Handing the existing port over for inspection

**Frozen until inspected:** no renderer, driver, audio or input changes to the port will be made
before its implementation has been examined. The HUD work in `app/` is a standalone module (a
`View`, a layout model and a preview activity) that does not wrap, replace or intercept the port's
rendering path, and it is only ever connected through `InputSink`.

## 1. What this environment can and cannot download (measured, not assumed)

Tested with `curl`/`wget` from the sandbox on 2026-11-10:

| Host | Result |
|---|---|
| `github.com` | **200** (and `git clone` verified working) |
| `codeload.github.com` | **200** (repository tarballs) |
| `api.github.com` | **200** |
| `raw.githubusercontent.com` | blocked (TLS reset) |
| `release-assets.githubusercontent.com`, `objects.githubusercontent.com` | blocked → **GitHub Releases and Git LFS assets cannot be downloaded** |
| Google Drive, Dropbox, MEGA, transfer.sh, 0x0.st, file.io, tmpfiles.org, pixeldrain, catbox, litterbox, gofile, filebin, SourceForge, dl.google.com, repo1.maven.org | all blocked (TLS reset / no resolution) |

**Conclusion:** a direct external download link will not work. Repository *commits* work; release
*assets* do not.

## 2. Three ways to get the build in, in order of preference

### Option A — attach the intake bundle in this chat (fastest, smallest)

Run this on your PC:

```sh
tools/apk-intake.sh /path/to/base.apk -o ~/intake
```

It writes `~/intake/apk-intake-<sha8>.zip`. For a normal launcher APK that bundle is **1–3 MB**
(text evidence only, no proprietary binaries), so it fits well inside the 25 MB chat limit.

Attach that zip here. It is enough to settle:

* whether the runtime is a Wine/Box64 container or a native arm64 port (contents + assets + dex +
  process-facing strings);
* what `libd3d9_gles.so` and `libdxvk_d3d9*.so` are (SONAME/NEEDED/RUNPATH + filtered dynamic
  symbols);
* whether the app bundles its own Vulkan loader (assets/`libvulkan*`, dxvk config, driver payloads);
* which driver/translation payloads are actually shipped (`.tzst`, `.wcp`, `.adpkg`, `libvulkan_*`).

### Option B — a private GitHub repository (needed if we must inspect the binaries themselves)

1. Create a **private** repo under the same GitHub account connected to this session
   (e.g. `bloodlines-intake`). Private matters: the APK contains third-party proprietary components.
2. Commit the APK (or the Tier-B bundle from `apk-intake.sh --with-libs`).
3. Tell me the repo name; I will `gh repo clone` it. Nothing from it is ever committed into this
   project's repository or uploaded anywhere else.

**Per-file limit:** GitHub rejects single files over 100 MB, and LFS assets are not downloadable from
here. If the APK is larger, split it and commit the parts:

```sh
split -b 90m base.apk apk.part.
sha256sum base.apk > base.apk.sha256
# commit apk.part.* and base.apk.sha256, then tell me the repo name
```

I will reassemble them (`cat apk.part.* > base.apk`) and verify against the checksum before touching
anything.

### Option C — device-side evidence instead of the APK

If neither the APK nor its libraries can leave your machine, run
[`tools/device-probe.sh`](../../tools/device-probe.sh) (no root needed) while the game is running and
send its archive. It answers the runtime questions from the *live* process instead of the file
contents: which Vulkan/GLES libraries are mapped, whether a Wine/Box64 process tree exists, and
whether the app owns its Vulkan loader. Combine with Option A for full coverage.

## 3. Exact file manifest (prioritised)

`tools/apk-intake.sh` produces all of P0 automatically. If you would rather assemble it by hand, this
is what matters, in order.

### P0 — decides the architecture (small, text only)

| # | Item | How | What it answers |
|---|---|---|---|
| 1 | `AndroidManifest.xml` readable | `aapt2 dump badging base.apk` or `apktool d base.apk` (no aapt2? `unzip -p base.apk AndroidManifest.xml \| strings -n 3`) | Package, activities, SDK levels, whether the launcher activity is SDL/GameActivity or a container shell; declared `uses-feature`s (Vulkan version, touchscreen, orientation) |
| 2 | Full APK entry listing | `unzip -l base.apk` | Wine/Box64/iOSyscall payloads, `.tzst`/`.wcp`/`.adpkg` components, `.bik`/`.vpk` game data, bundled `libvulkan*`, `dxvk.conf` |
| 3 | Per-library metadata + symbols, for `libmain.so`, `libSDL3.so`, `libdxvk_d3d9.so`, `libdxvk_d3d9_legacy.so`, `libd3d9_gles.so` and any `libvulkan*`/`libX11*`/`libc++_shared.so` | `readelf -d <lib>` (NEEDED/SONAME/RUNPATH), `readelf -h <lib>`, `nm -D --defined-only <lib> \| grep -iE 'vk_icd\|vkCreateInstance\|IDirect3D\|Direct3DCreate9\|SDL_\|JNI_OnLoad\|wine\|box64\|panfrost\|gladio\|vortek'` | Whether each library is an ICD, a D3D9 front end, a presentation proxy, or a JNI shim — i.e. the `libd3d9_gles.so` question in report §2.4 |
| 4 | Assets listing + the small text/config assets verbatim | `unzip -l base.apk \| grep assets/`; then contents of `*.conf`, `*.json`, `*.ini`, `*.rc`, `*.txt` under `assets/` (skip anything big) | The configured renderer (`dxvk.conf`, container JSON), Box64/Wine presets, driver selection defaults, screen/resolution defaults |
| 5 | Targeted strings from the key libraries + `classes*.dex` | `strings -n 6 <lib> \| grep -iE 'vulkan\|dxvk\|box64\|wine\|SDL\|panvk\|turnip\|gladio\|vortek\|zink'` (script does this) | Confirms the backend names and code paths actually present |
| 6 | **A logcat + audio dump from a real run** | `adb logcat -v threadtime > logcat.txt` (start before launching; capture ~30 s gameplay + ~30 s of a cutscene), plus `adb shell dumpsys media.audio_flinger > audio.txt` | The cheapest, highest-value evidence for the audio/cutscene work: negotiated sample rate/format/buffer, underruns, decode timing, driver selection messages |

### P1 — only if P0 leaves the architecture ambiguous

| # | Item | Why |
|---|---|---|
| 7 | `lib/arm64-v8a/*.so` (Tier B: `apk-intake.sh --with-libs`) | Disassembly/string-level analysis of the D3D9 and input paths; needed before any code-level change to them |
| 8 | `assets/` payload manifests *inside* the `.tzst` files (`tar --zstd -tf`) | Which Wine build, which Box64, which DXVK version, which graphics driver components ship |

### P2 — needed later, for reproducing builds (not for the investigation)

| # | Item | Why |
|---|---|---|
| 9 | The port's source tree, or the name+link of the upstream project it was built from | To build from source rather than patch a binary |
| 10 | The build system files (Gradle/CMake/meson, NDK version, prebuilt blob list) | Reproducible builds, CI integration |
| 11 | `adb shell dumpsys SurfaceFlinger \| grep -iE 'GLES\|Vulkan'` and `/vendor/lib64/hw/` listing | Stock driver version/features (Mali DDK level), Vulkan API level |

## 4. What I will do with it

1. Fill `docs/reference/runtime-profile.md` — every row sourced from your artifacts.
2. Retire one of the two architecture hypotheses in report §2 and correct the report where it was
   wrong (explicitly, in the document, rather than quietly).
3. Answer report §4.4/§4.7: whether this runtime can load a custom Vulkan ICD at all, which is the
   single gate on any PanVK work.
4. Only then propose the first runtime change — smallest, measured, reversible, with the current
   working path untouched as the fallback.

## 5. Handling rules I will follow

* Your APK and its libraries stay outside this repository's git tree; nothing proprietary is
  committed to GitHub.
* No blind copying of driver binaries from third-party releases.
* Nothing replaces the working graphics path until an alternative has been tested on the device.
