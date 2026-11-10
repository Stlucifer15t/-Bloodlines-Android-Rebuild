# Bloodlines Android Rebuild

An Android rebuild/improvement project for the existing **Vampire: The Masquerade – Bloodlines**
Android port: better and more stable frame rate, correct audio/video sync, and a modern, properly
designed mobile touch interface — on a Mali-G52 (Bifrost) device, without sacrificing the game's
original visual quality.

Target device: Android 14, MediaTek Helio G85 (MT6769V/CZ), **Mali-G52 MC2**, 8 GB RAM, arm64.

---

## Status

**Assignment 001 — investigation — delivered. The port itself is not in this repository yet.**

| | |
|---|---|
| Investigation report | [`docs/reports/001-technical-investigation.md`](docs/reports/001-technical-investigation.md) |
| Device evidence tool | [`tools/device-probe.sh`](tools/device-probe.sh) |
| HUD foundation + preview/customiser | [`app/`](app/) (Assignment 004, in progress) |
| Build notes | [`docs/reference/build.md`](docs/reference/build.md) |

Build/test status: **green** — `:app:testDebugUnitTest` 25/25 passing, `:app:assembleDebug`
producing a debug APK (GitHub Actions, JDK 17 / Gradle 8.9 / AGP 8.5.2 / Kotlin 1.9.24).

> **Blocker, stated plainly:** this repository contains one committed file (a README) before this
> work — no port sources, no APK, no `libmain.so` / `libSDL3.so` / `libdxvk_d3d9*.so` /
> `libd3d9_gles.so`, no build system, no prior APK research. So Assignment 001 §1 ("inspect the
> complete repository") and §4 could only be answered as candidate architectures plus a decision
> procedure, never as a description of the shipped binary. See report §0.1.
>
> To unblock: supply the **APK** (or its `lib/arm64-v8a/` slice) and/or the port's source tree, and
> run `tools/device-probe.sh` on the device. That closes D1–D7, after which the driver, audio and
> frame-pacing work in the report's plan becomes implementable rather than speculative.

## What is in here

```
docs/
  reports/001-technical-investigation.md   the required first deliverable
  reference/runtime-profile.md             fill-in template for device evidence
  reference/build.md                       toolchain + module layout
app/                                       HUD overlay + standalone preview/customiser
tools/device-probe.sh                      no-root evidence collection over adb
.github/workflows/android-build.yml        CI: unit tests + debug APK
```

## Key technical findings (summary)

* **PanVK is feasible on this GPU class, but not yet justified as a default.** All three referenced
  projects support Mali-G52 MC2 over stock `mali_kbase` with no root and no custom kernel; two of
  them have been validated with DXVK D3D9 inside Winlator-family runtimes. Details and caveats
  (DXVK version ceiling on Bifrost, force-advertised features, known DX9 sky artefacts, unproven
  speed) are in report §4.
* **The decisive unknown is the loader, not the driver.** Android's system Vulkan loader resolves
  the ICD through `hw_get_module("vulkan")` and ignores `VK_ICD_FILENAMES`; an unrooted app can only
  use a bundled driver if *it* owns the loader (container-private `libvulkan.so.1`, an AdrenoTools
  style shim, or `SDL_Vulkan_LoadLibrary`). Report §4.4 states the test that settles it.
* **An unexpected PanVK advantage:** Mali-G52 decodes BC1–BC3 in hardware, and Bloodlines textures
  are DXT1/DXT3/DXT5. On a driver without BC support this is a CPU transcode plus memory cost — for
  an 8 GB device, a real win if PanVK proves stable.
* **Cutscene FPS is the highest-value fix.** Gameplay 25 FPS vs cutscenes 8–10 FPS is a 2.5–3×
  collapse that a single fullscreen video quad cannot explain; the Bink decode/upload path carries a
  per-frame CPU cost the gameplay path does not, which also explains the A/V drift. Report §6.3, §7.

## Building

```sh
gradle :app:testDebugUnitTest    # pure-JVM tests (no device, no SDK needed for these)
gradle :app:assembleDebug        # debug APK
```

CI runs both on every push. See [`docs/reference/build.md`](docs/reference/build.md) for why the
Gradle wrapper is not committed.

## Ground rules this project follows

* The working game is preserved: no unmeasured rewrites, no default quality reductions, no replacing
  the working graphics driver before an alternative has passed testing.
* No game assets, APKs or proprietary binaries in git. Open-source components are built from source
  with licences and notices retained.
* Every change keeps the tree buildable and adds tests where the logic is testable without a device.
