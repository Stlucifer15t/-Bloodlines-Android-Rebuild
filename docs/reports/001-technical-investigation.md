# Assignment 001 — Technical Investigation Report

**Project:** Bloodlines Android Rebuild
**Report date:** 2026-11-10
**Target device:** Android 14, TECNO, MediaTek Helio G85 (MT6769V/CZ), Mali-G52 MC2 (Bifrost, arch v7, JM), 8 GB RAM, arm64-v8a
**Status:** Investigation complete **for the evidence that exists**. Sections 1 and 4.4 are blocked by missing artifacts — see §0.1.

---

## 0. How to read this report

Every non-trivial statement carries one of four labels:

| Label | Meaning |
|---|---|
| **[C]** Confirmed | Backed by a primary source I could actually read (upstream repository, Mesa docs, Android/Khronos documentation, the GitHub API for this repo). Source listed in Appendix B. |
| **[I]** Inferred | Follows from confirmed facts by engineering reasoning. High confidence, but not directly observed. |
| **[H]** Hypothesis | Plausible explanation, must be tested on the device or against the real artifact before any code depends on it. |
| **[U]** Unverified | Cannot be resolved without the APK / port sources / a device session. Explicitly *not* used as a basis for changes. |

This project's rules say: preserve the working game, do not lower quality by default, do not copy driver binaries blindly, keep the tree buildable, and separate confirmed findings from assumptions. This report is written to that rule set, which is why it spends most of its length on *what must be measured* rather than guessing at fixes.

### 0.1 Critical blocker (read this first)

The repository `Stlucifer15t/-Bloodlines-Android-Rebuild` **does not contain the Android port**.

What I verified (GitHub API, 2026-11-10):

```
repo size          : 0 KB
default branch     : main
commits            : 1  ("Initial commit", 8a1fc61)
files in tree      : README.md only (30 bytes: "# -Bloodlines-Android-Rebuild")
branches           : main
workflows          : none (.github/workflows → 404)
releases / issues  : none
```

**[C]** There is no source tree, no `libmain.so`, no `libSDL3.so`, no `libdxvk_d3d9*.so`, no `libd3d9_gles.so`, no APK, no build script, no NDK/CMake configuration, no APK research reports and no exported-symbol dumps in this repository.

Consequences, stated plainly:

1. Assignment instructions "inspect the complete repository", "inspect existing APK research reports and exported symbols" and "identify the actual build system" **cannot be satisfied yet** — there is nothing to inspect. Anything I wrote claiming to describe *this port's* internals would be fabrication.
2. Item 4 of the required deliverable ("how D3D9 rendering reaches the Android GPU") can only be answered today as a **set of candidate architectures plus a decision procedure**, not as a description of the shipped binary. That is what §2 and §3 provide.
3. Every optimisation in §6, §7 and §8 is therefore specified as a **measurement plus a conditional fix**, not as a patch. This is deliberate: on a game that "already works", unmeasured changes are regressions waiting to happen.
4. The parts of the assignment that are genuinely actionable without the artifact — PanVK feasibility, Mali-G52 constraints, control-map design, HUD architecture — are answered concretely below, and the repo now contains the scaffolding, the device-probe tooling and the HUD foundation to receive the port when it arrives.

**What unblocks the rest:** the APK (or its `lib/arm64-v8a/` slice + `AndroidManifest.xml`), and ideally the port's source tree or the name/link of the upstream project it was built from. `tools/device-probe.sh` (in this repo) collects exactly the evidence needed from a connected device, including from a *release* APK where only `run-as`-free inspection is possible.

---

## 1. Evidence inventory

### 1.1 What I could inspect

| Source | What it establishes |
|---|---|
| This repository (git + GitHub API) | Empty except README. §0.1. |
| `JimVulkan/mali-panvk` README **[C]** | Mesa PanVK ported to Arm's `mali_kbase` (`/dev/mali0`), no root, no custom kernel, no `/dev/dri`; Bifrost v7 (G31/G51/G52/G71/G72/G76) supported, Vulkan 1.4 (1.3 on the smallest Bifrost parts); DXVK D3D9/10/11 through `VK_EXT_robustness2`; **BC1–BC3 decoded by G52 in hardware**, BC4–BC7 emulated; disk shader cache; ships a `dist/` zip (`meta.json` + `libvulkan_panfrost.so`) for loaders that accept a custom ICD; explicitly states it opens `libui.so` at runtime so it still loads under **AdrenoTools, Bannerlator and Winlator**, where it falls back to reading Arm/MediaTek buffer handles directly. |
| `zenithblue-oss/panvk-kbase-android` README + releases **[C]** | Same kbase approach, universal driver for v6–v14. **Tested on Mali-G52 MC2: Galaxy A31 (Helio P65)** — PanProbe 41/41, PanPlay D3D9 cube pass. Notes a **D3D9 −26 % regression on one G52 run vs its own build 116** ("not confirmed"). Ships `PanPlay`, a Wine/FEX-Emu launcher with its own X server, i.e. the same class of runtime as this port's likely ancestors. Documents Zink path (D3D9→WineD3D→OpenGL→Zink→Vulkan). |
| `LukeValen/panvk-mali-g52` README **[C]** | Bring-up explicitly targeting **MT6769 (Helio G85 class), Mali-G52 r1 MC2, kernel 4.19.191, kbase UAPI 11.38, Android 14, no root** — the closest published match to the target device. Reports working: kbase init, memory management, compute, vertex/tiler/fragment submission via `KBASE_IOCTL_JOB_SUBMIT`, offscreen render + readback, X11 WSI, AHardwareBuffer import, **Winlator/Ludashi on-screen VKCube**, and **DXVK D3D9/10/11 basic rendering**. Explicitly warns that it **force-advertises capabilities (geometry/tessellation/MDI/BC/transform feedback) that are not implemented for G52/JM**, and that its submission path prioritises correctness over performance. |
| Mesa Panfrost/PanVK documentation **[C]** | Panfrost GLES is Khronos-conformant on G52/G57/G610. PanVK is conformant **only on Mali-G610**; on Bifrost it is experimental and upstream-gated behind `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1`. Upstream PanVK expects the DRM Panfrost/Panthor interface. |
| Khronos Vulkan Loader docs + Android loader behaviour **[C]** | Desktop loader honours `VK_ICD_FILENAMES`; **the Android system loader does not** — it resolves the ICD through `hw_get_module("vulkan")` from `/vendor/lib64/hw/`, and "due to security policies in Android, none of this can be modified under normal use". Unrooted custom-ICD loading therefore requires the app to bring **its own loader** (AdrenoTools/libadrenotools or vendored Khronos loader) — which is precisely why Winlator-family runtimes can use Turnip/PanVK: inside the container, `libvulkan.so.1` resolves from the app's own library directory, not from `/system`. |
| FristOneRR / community PanVK-for-DXVK notes **[C]** | Device matrix lists **Helio G85 / Mali-G52 MC2 (Oppo A38, Redmi 13C) as working**. DXVK ceiling on Bifrost: **DXVK 1.x reliably; 2.x up to 2.4.1 per one source; 2.5+ freezes/does not start**. Known Bifrost defect: **"some DX9 games show black/blue blocks in the sky"**. BCn→ASTC conversion has caused freezes/reboots — must be left off. |
| PCGamingWiki, Bloodlines **[C]** | Engine **Source 2004**; graphics **Direct3D 9**; audio middleware **Miles Sound System 6.5e**; cutscene middleware **Bink Video 1.6d**. Shipped DLLs include `binkw32.dll`, `Mss32.dll`, `vaudio_miles.dll`, `voice_miles.dll`, `vpython21.dll` (Python 2.1 scripting), `engine.dll`, `client.dll`, `tier0/vstdlib/vgui2`. |
| Winlator lineage (Wine + Box64 + XServer; Gladio = OpenGL-over-GLES wrapper for Mali; Vortek = custom Vulkan path for Mali) **[C]** | Establishes the naming conventions and the range of plausible runtimes for a "D3D9 on Android" port, and explains why `textureCompressionBC` emulation and "T-pose/vertex-explosion fixes for Mali" appear in those changelogs — the same Mali constraints will apply to this port. |

### 1.2 What I could not inspect (and why it matters)

* The APK: `lib/arm64-v8a/*.so`, `AndroidManifest.xml`, `assets/`, `classes*.dex`, `lib/arm64-v8a/*.so` symbol tables, any `.json`/`.ini`/`.conf` config assets.
* Any research reports / symbol dumps produced by your earlier APK analysis (they are not in this repo).
* The port's build system (Gradle/CMake/Ninja/meson, NDK version, prebuilt blobs).
* Anything on the device: `vulkaninfo`, `dumpsys SurfaceFlinger`, `/vendor/lib64/hw/vulkan.*`, kbase version, actual frame traces.

`tools/device-probe.sh` closes the device half of this gap in one command.

### 1.3 Sandbox limitation affecting this report's build claims

This working environment has **no JDK, no Android SDK/NDK, and no access to Maven/Google Maven** (only GitHub/PyPI/npm mirrors are reachable). Therefore *no Android build can be produced or verified here*. All build verification for this repository happens in GitHub Actions (§10.1). I will not claim a build passed unless a CI run says so.

---

## 2. Existing Android runtime architecture

### 2.1 The problem

A PC-only 2004 x86 Windows game cannot run on Android without at least three translations: **CPU** (x86 → arm64), **OS** (Win32 → Linux/Android), **graphics** (D3D9 → something the Mali driver speaks). The five libraries named in the assignment tell us which technologies were chosen, but two very different architectures fit those names.

### 2.2 Hypothesis A — Wine/Box64 container (Winlator family)

```
  Android app (Java/Kotlin shell + native X server, e.g. "XServer")
     └── guest process: wine (x86 PE, 32-bit)  ── Box64/Box86 translates x86 → ARM64
           ├── vampire.exe / engine.dll / client.dll   (Source 2004, D3D9)
           ├── d3d9.dll  → DXVK (PE build) → vulkan-1.dll → container libvulkan.so.1
           │                                                  └─ ICD: Turnip | Vortek | PanVK
           ├── binkw32.dll  (Bink 1.6d, x86 SIMD inside Box64)
           ├── Mss32.dll    (Miles 6.5e) → dsound → winealsa/pulse → host audio
           └── vpython21.dll
     └── native: libSDL3.so (container UI/audio/input), libmain.so (app entry), libd3d9_gles.so (?)
```

**Supporting evidence [I]:** `libdxvk_d3d9.so` and a `*_legacy` variant are exactly how Winlator-family builds and DXVK-Native builds name D3D9 translation libraries; "legacy" mirrors the Winlator pattern of shipping an older DXVK (1.x) for old Vulkan drivers — the same split the community PanVK notes recommend for Bifrost (DXVK 1.x vs 2.x). Multiple PanVK projects explicitly test inside Winlator/Ludashi and provide ICD packages "for other loaders".

**Counter-evidence [I]:** a Wine container would normally ship a PE `d3d9.dll` inside an asset archive rather than a host-native `libdxvk_d3d9.so` at the APK root, and `libmain.so` + `libSDL3.so` at the APK root look like a native-app entry point, not a container shell.

### 2.3 Hypothesis B — native arm64 engine port with DXVK-Native

```
  Android app (SDL3 Activity: libmain.so + libSDL3.so)
     └── engine already compiled for arm64-v8a (Source 2004 port):
           ├── D3D9 device layer → libdxvk_d3d9.so  (DXVK-NATIVE, SDL3 WSI, no Wine)
           │        └── Vulkan → libvulkan.so (system loader) → /vendor/lib64/hw/vulkan.mali.so
           ├── fallback: libd3d9_gles.so (D3D9 → EGL/GLES 3.x → same Mali driver)
           ├── libdxvk_d3d9_legacy.so = older ABI/feature-level path for old drivers
           ├── Bink / Miles replaced by SDL3-based decoders + vendored or reimplemented pieces
           └── touch → SDL3 events → engine input
```

**Supporting evidence [I]:** DXVK's own issue tracker documents DXVK-Native's `libdxvk_d3d9.so` naming and its per-WSI ABI split (SDL2/GLFW/SDL3) — a native DXVK build paired with SDL3 in one APK matches all five library names at once. `libd3d9_gles.so` as a *sibling* of DXVK only makes sense if the app chooses between two D3D9 backends at runtime (which also explains why the working game runs at all on a device where BCn decoding is the usual killer). A native port also makes 25 FPS on a 2-core G85 plausible; full x86 emulation of Source 2004 on a G85 at 25 FPS would be unusually good but not impossible.

**Counter-evidence [I]:** Source 2004 is not open source; a native port requires either reimplementing engine DLLs (`engine.dll`, `client.dll`, `vgui2.dll`, Python 2.1 scripting, `vphysics`) or shipping them under emulation. There is no widely known native Bloodlines Android port, which makes "native engine" the bolder claim.

### 2.4 Component responsibility map

Both hypotheses agree on three of the five libraries. The two disputed ones are marked.

| Library | Almost certainly | Why | Confidence |
|---|---|---|---|
| `libmain.so` | App/engine native entry point loaded by the Java `Activity` (conventional `System.loadLibrary("main")` + `nativeMain`). Owns the game loop, window/surface lifecycle, and asset mounting. | Name and position are boilerplate for SDL/GameActivity-style apps. | **[I]** high |
| `libSDL3.so` | Windowing/surface, input event pump, audio device, and (on Android) lifecycle + `AAssetManager` glue. In a container runtime it would instead be the shell's UI/input/audio layer. | SDL3 on Android is the standard way to get a `ANativeWindow`, a `AAudio`/`OpenSL` device and multi-touch events. | **[I]** high |
| `libdxvk_d3d9.so` | D3D9 → Vulkan translation (DXVK). Current/preferred variant. | Name is DXVK's; D3D9 is the game's API. | **[I]** high |
| `libdxvk_d3d9_legacy.so` | Second DXVK build kept for old/limited Vulkan drivers: either an older DXVK (1.x, Bifrost-safe) or a reduced feature-level build. Exists because the stock Mali Vulkan driver lacks features the modern DXVK build requires. | Matches the community Bifrost ceiling (DXVK ≤ 2.4.1, 1.x safest) and Winlator's "legacy DXVK" option. | **[I]** medium-high |
| `libd3d9_gles.so` | **Disputed.** Either (a) a D3D9 → GLES translator used as the safe fallback on Mali (avoids BCn and robustness2 requirements; GLES 3.x is what `libGLES_mali.so` does best), or (b) a helper/proxy that presents DXVK output through GLES/EGL, or (c) part of a Gladio-style OpenGL-over-GLES wrapper. | The only component whose purpose cannot be settled from public sources; `libd3d9_gles` appears in **no** public GitHub code search result (0 hits). | **[U]** |

Note the important corollary: if the *working* path is (a) — D3D9 → GLES — then **Vulkan is not on the game's rendering path at all**, and PanVK can only matter after a working DXVK path is introduced (§4.7).

### 2.5 Decision procedure — five measurements that settle the architecture

These are ordered by information gained per unit of effort and are all in `tools/device-probe.sh`.

| # | Measurement | Result that implies Hypothesis A | Result that implies Hypothesis B |
|---|---|---|---|
| D1 | `unzip -l app.apk \| grep -E 'assets/.*(wine|box64|box86|\.tzst|\.wcp|rootfs)'` | Wine/Box64 assets present → A | absent → B |
| D2 | `unzip -l app.apk \| grep lib/arm64-v8a` | `libbox64.so`/`libwine*` → A | only the five named libs (+ maybe `libc++_shared.so`) → B |
| D3 | During a cutscene: `adb shell ps -A -o PID,NAME,ARGS \| grep -iE 'vampire|wine|box64'` | guest `wine`/`vampire.exe` process tree → A | single app process, no wine → B |
| D4 | `adb shell cat /proc/<pid>/maps \| grep -E 'vulkan\|GLES\|dxvk\|d3d9'` | `d3d9.dll`-style PE mapping + container `libvulkan.so.1` | `libdxvk_d3d9*.so` + `/system/lib64/libvulkan.so` | 
| D5 | `nm -D --defined-only lib/arm64-v8a/libd3d9_gles.so \| head -50` (and same for the DXVKs) | `IDirect3D9`-ish C++ mangled symbols → real translator | `vkCreateInstance`/WSI shims only → presentation proxy |

For D5 the decisive signal is the **presence of `vk_icdGetInstanceProcAddr` / `vk_icdNegotiateLoaderICDInterface`** in a candidate driver library (ICD) versus `IDirect3DDevice9` methods (D3D9 front end).

---

## 3. Current graphics rendering pipeline

### 3.1 What the game asks the GPU to do (engine side) **[C]/[I]**

Source 2004 on D3D9, shipped in 2004, so:

* Shader Model 1.1/2.0 era shaders; the fallback path is large and state-heavy.
* **Textures are DXT1/DXT3/DXT5** (`BC1/BC2/BC3`) almost everywhere plus uncompressed UI art.
* Per-frame passes include world (with its own depth prepass/openGL-ish sorting), **lightmaps**, **dynamic lights**, **bloom/HDR post-process**, water/reflection render targets, and `vgui2` UI composited over 3D. Multiple **render-to-texture** targets per frame are normal for this engine.
* Terrain is split into visleaves with potentially high draw-call counts and modest triangle sizes — i.e. **draw-call-bound, not triangle-bound**.
* Cutscenes are **Bink 1.6d video** (`binkw32.dll`) drawn as a fullscreen quad, frequently *concurrently* with engine work.

### 3.2 How that reaches the Mali GPU (candidate paths)

**Path V (Vulkan):**
```
D3D9 calls → DXVK (libdxvk_d3d9*.so) → SPIR-V shaders → VkCommandBuffer
   → libvulkan.so (system loader) → /vendor/lib64/hw/vulkan.mali.so (Arm DDK)
   → kbase (/dev/mali0) → Bifrost v7 JM → tiler → 2 shader cores → AFBC → compositor
```
**Path G (GLES):**
```
D3D9 calls → libd3d9_gles.so (translate state+shaders) → EGL/GLES 3.x
   → libGLES_mali.so → kbase → Bifrost → SurfaceFlinger
```
**Path P (PanVK, experimental):**
```
DXVK → VkCommandBuffer → <custom loader> → libvulkan_panfrost.so (Mesa PanVK, kbase backend)
   → /dev/mali0 (kbase UAPI) → Bifrost v7 JM
```

### 3.3 Why the path matters for every optimisation in the assignment

| Question | Path V | Path G | Path P |
|---|---|---|---|
| Can we cache compiled pipelines? | Yes: DXVK state cache + driver pipeline cache (stock Mali: yes; PanVK: disk shader cache). | Only whatever the GLES translator exposes. | Yes, plus `MESA_SHADER_CACHE_DISABLE` control. |
| Can we hit BC1–BC3 without CPU transcode? | **Depends on the driver.** Stock Mali ADRENO-class Android DDK supports `textureCompressionBC` only on some parts; on G52 it is commonly absent, forcing DXVK to emulate → CPU cost + memory. | Translator-dependent (often CPU transcode to ETC2/ASTC). | **Yes in hardware** — PanVK on G52 decodes BC1–BC3 (`[C]`), BC4–BC7 emulated. |
| Cheap full-screen post-process? | Yes, but bandwidth-bound on the tiler; each RTT forces tile store/restore, worsened by RGBA16F. | Same. | Same, plus driver maturity costs. |
| Present/frame-pacing control? | Swapchain present mode + `dxvk.maxFrameLatency` + `d3d9.numBackBuffers`. | EGL swap interval only. | Swapchain modes + Kbase sync path (currently CPU-synchronous → a real cost, `[C]` from LukeValen). |

**This table is the reason §4 does not end with "adopt PanVK".** PanVK's hardware BC decode is genuinely attractive for a DXT-heavy 2004 game on a GPU with no BC support; but PanVK's G52 submission path is synchronous and force-advertises unimplemented features, and its own published G52 D3D9 number was **−26 %** in one run. The correct conclusion is: **worth an optional backend behind a robust fallback, not a default.**

---

## 4. PanVK compatibility assessment

Each of the seven questions from the assignment, answered with a confidence label.

### 4.1 Do the available PanVK builds support Mali-G52 MC2? — **[C] Yes, all three.**

| Project | G52 MC2 | Basis |
|---|---|---|
| `JimVulkan/mali-panvk` | Yes (v7 Bifrost family; "tested on G76, G72, **G52**") | README support table |
| `zenithblue-oss/panvk-kbase-android` | Yes — **Galaxy A31, Mali-G52 MC2**, PanProbe 41/41, PanPlay D3D9 cube pass | README tested-device table |
| `LukeValen/panvk-mali-g52` | Yes — **the project's entire purpose**, MT6769 / G52 r1 MC2 / Android 14 / no root | README |

Nearest match to the target device class: LukeValen targets **MT6769, which is the Helio G85's part family**, kernel 4.19.191, **kbase UAPI 11.38**.

### 4.2 Do they support stock Android `mali_kbase`? — **[C] Yes; that is the whole point of all three.**

All three replace upstream PanVK's DRM requirement (Panfrost/Panthor, `/dev/dri/*`) with direct use of `/dev/mali0` and the vendor kbase UAPI. Practical dependency: **the kbase UAPI version must be supported**. On MT6769/G85 kernels this is the `11.x` family (`11.38` observed on the reference device). If the TECNO's kernel exposes an older/newer kbase UAPI, atom stride detection (`PANVK_ATOM_STRIDE`, auto-detected in newer FristOneRR/LukeValen builds) and IOCTL differences become the failure mode. **[I]** must be checked with `cat /sys/kernel/debug/mali0/version` (root) or by `PANVK_DEBUG=startup` device-creation logging (no root).

### 4.3 Can they run without root or a custom kernel? — **[C] Yes, by design and by reported testing.**

No root, no custom kernel, no `/dev/dri`. They read `/dev/mali0` (world-accessible on most Mali Android builds) and present via gralloc/AHardwareBuffer. This is *not* a privilege escalation path: the driver is an ordinary vendor-ABI consumer of kbase.

### 4.4 Can the current runtime load a custom Vulkan driver? — **[U] Cannot be answered without the artifact. Here is exactly what determines it.**

This is the decisive question, and unlike 4.1–4.3 it depends on *this port*, not on PanVK.

**[C]** Android's system Vulkan loader resolves its ICD through `hw_get_module("vulkan")` from `/vendor/lib64/hw/`, ignores `VK_ICD_FILENAMES`, and cannot be redirected by an unrooted app. So a custom ICD is only usable if **the process' `libvulkan.so`/`libvulkan.so.1` is not the system one** — which happens in exactly three situations:

| Situation | Mechanism | Applies when |
|---|---|---|
| **L1. Container runtime** | The Wine/Box64 guest resolves `libvulkan.so.1` from the app's own library dir; the app supplies a Khronos loader + ICD json (`VK_ICD_FILENAMES` honoured) or a direct ICD bind. This is how Turnip and Winlator's Vortek work, and LukeValen's build reports VKCube+DXVK working "inside Winlator/Ludashi". | Hypothesis A (§2.2) |
| **L2. App-bundled loader / hooking** (AdrenoTools-style) | The app `dlopen`s the ICD itself and exposes the Vulkan entry points through its own dispatch. JimVulkan explicitly keeps working under "AdrenoTools, Bannerlator, Winlator" linker namespaces — i.e. it expects to be loaded *this way*. | Hypothesis B, requires app change (small, contained) |
| **L3. SDL3 explicit path** | SDL3 has `SDL_Vulkan_LoadLibrary(path)`; with a vendored Khronos loader (or an ICD-direct shim) the app can point Vulkan at `libvulkan_panfrost.so`. Costs ~1 MB in the APK. | Hypothesis B, requires app change |

**Determination:** run `tools/device-probe.sh` D4/D6 — check whether the app process maps `/system/lib64/libvulkan.so` (⇒ L2/L3 needed) or an app-private `libvulkan.so.1` (⇒ L1 available, drop-in ICD).

### 4.5 Can the existing D3D9→Vulkan layer use PanVK? — **[C] Yes, in principle, and this has been demonstrated twice.**

* LukeValen: "**DXVK Direct3D 9/10/11 basic rendering** validated through Winlator/Ludashi" on G52 MC2.
* zenithblue: PanPlay's D3D11/D3D9 cubes through `DXVK` on a **Mali-G52 MC2**, and the README states `VK_EXT_robustness2` with null descriptors is implemented *because* DXVK and zink require it.

Caveats that matter for *this* game **[C]/[I]**:
* **DXVK version ceiling on Bifrost: 1.x safest, 2.4.1 reported working, 2.5+ fails/freezes.** The port already shipping a `_legacy` DXVK variant is consistent with this.
* **Known Bifrost defect: black/blue blocks in the sky in some DX9 titles.** Bloodlines' sky is a shader-driven skybox — this is the single most likely visual regression on PanVK, and it is a *quality* regression, which the assignment forbids as a default trade.
* **BCn→ASTC conversion can freeze/reboot the device** on some builds — must stay off; native BC1–BC3 decode is the reason to be on PanVK at all, so this is compatible with the goal.
* LukeValen **force-advertises geometry/tessellation/MDI/transform feedback** that are not implemented for G52/JM. D3D9 games at SM2.0 rarely need them; a driver that lies about features can still crash. This is inherently an experimental backend.
* Performance evidence is thin and mixed: zenithblue measured **D3D9 −26 %** on G52 (one run, unconfirmed); PanProbe showed large wins on Valhall, not Bifrost. **[I]** PanVK on G52 today is a *compatibility and BCn* play, not a proven FPS win.

### 4.6 Are Android's Vulkan loader and the runtime's driver-loading mechanism compatible? — **[C] Not directly; only through L1/L2/L3 above.**

Answering precisely, because the assignment asks: the *interface* is compatible (PanVK-on-kbase builds the standard Android ICD exports + gralloc/AHardwareBuffer import, and JimVulkan even handles MediaTek AFBC-compressed gralloc buffers), but the *discovery mechanism* is not. The system loader will never see a bundled driver. So integration is an app-side change, not a driver drop-in — for a container runtime it is a config entry, for a native app it is a loader shim.

### 4.7 Does the game's rendering path actually use Vulkan in a way that makes PanVK useful? — **[U] Depends on §2.**

Decision tree:
* If the working path is **DXVK → Vulkan** (Path V): PanVK is directly substitutable. Useful. Benefits: hardware BC1–BC3, controllable shader cache, JIT-free Mali DDK paths. Costs: immature submission path, feature lying, sky artefacts, unproven speed.
* If the working path is **D3D9 → GLES** (Path G) with DXVK present but unused/fallback: PanVK can only help *after* the DXVK path is made the primary path — a two-step change with real regression risk. PanVK would then be a third backend (G/V/P), which is exactly the settings architecture §9 proposes.
* If the runtime has **no loader we control** (system-only libvulkan): PanVK is unusable without adding a loader; the honest answer is "blocked by Android loader policy", documented, with Voron-2/Turnip-style alternatives not available on Mali.

### 4.8 Verdict

**Technically feasible, not yet justified as a default.** Implement as **optional backend `panvk-kbase`, off by default, gated by a device probe, with automatic fallback to the current path** — and only after §2.5 identifies a controllable Vulkan entry point. Do not replace the working driver. Do not ship third-party driver binaries copied from releases; build from source in CI (all three projects are MIT/Mesa-derived and provide build scripts) or integrate a pinned, checksummed artifact loaded from user storage at the user's explicit action, with license/notice files retained. **[C]** licenses: Mesa/PanVK MIT; `JimVulkan` changes MIT; AOSP gralloc headers Apache-2.0; notice obligations listed in each project's `NOTICE`/`THIRD_PARTY_NOTICES`.

---

## 5. Mali-G52 specifics for this project

**Device facts [C]** (public, from the assignment's target spec + SoC data): MT6769V/CZ, 2× Cortex-A75 + 6× A55, Mali-G52 MC2 @ ~1 GHz, LPDDR4X (~13 GB/s class), Bifrost arch v7, Job Manager (no CSF), **no hardware geometry/tessellation shaders**, 2 shader cores, 64 KB-ish tile path with AFBC support.

Consequences that drive every optimisation in §6:

| Constraint | Engineering implication |
|---|---|
| **Tile-based rendering** | Any mid-frame transition to a render target and back forces tile store/restore. Full-screen bloom/HDR chains in Source 2004 are the main suspects for bandwidth stalls. Prefer fewer, larger passes; keep post-process targets RGBA8 where the art allows; enable AFBC for RTTs if the driver will. |
| **No BCn in stock Vulkan/GLES** | Bloodlines is DXT1/3/5. Either the runtime transcodes on CPU (load-time cost + 2–8× memory) or it uses a driver with hardware BC decode (**PanVK/G52 = BC1–BC3**). This is the strongest technical argument for PanVK, and also a potential *memory* win on an 8 GB device. |
| **No geometry/tessellation units** | Irrelevant for D3D9 SM2.0 content *if* the game does not use them; DXVK will emulate if requested. A driver that advertises these (LukeValen) is riskier than one that does not. |
| **2 shader cores, ~1 GHz** | Fill-rate and shader ALU are the scarce resources. Resolution scale is a legitimate *optional* setting, never a default. |
| **Bandwidth ~13 GB/s shared** | Avoid per-frame texture re-upload (cutscene video frames!), avoid CPU readback, prefer `VK_IMAGE_TILING_OPTIMAL` + linear staging with batched uploads. |
| **kbase UAPI version dependence** | PanVK's kbase backend is version-sensitive (atom stride, ION vs dma_heap). Log `PANVK_DEBUG=startup` on first run and store it in diagnostics. |
| **No root** | All diagnostics must be no-root: `dumpsys`, `/proc/<pid>/maps`, app-private logs, `PANVK_DEBUG`, DXVK HUD. |

---

## 6. Performance bottlenecks

### 6.1 Measurement first — the instrumentation plan

Before touching code, the port must be able to answer "where did this frame go?" Target instrumentation (all cheap, all toggleable, all off by default in release):

1. **Frame-time breakdown** per subsystem: input → game logic/simulation → render submission → present, with the present/`vkQueuePresentKHR` wait separated from submission. Bloodlines' engine already has a frame loop, so a rolling 512-frame ring of µs timings plus p50/p95/p99 is enough.
2. **DXVK HUD** (`DXVK_HUD=fps,frametimes,gpuload,api,descriptors`) — works on any DXVK path, no code needed (`[C]`).
3. **Mesa/PanVK logging** (`PANVK_DEBUG=startup`, `MESA_LOG=file`) and stock-driver perf counters if exposed.
4. **CPU profile** of the render thread + audio thread + decode thread (`simpleperf` on device, no root for debuggable builds; `perfetto` for scheduling/atrace).
5. **Stutter log**: every frame > 2× median, with the reason code (pipeline creation, texture upload, shader compile, GC, page fault, audio underrun).

`tools/device-probe.sh --trace` captures (1)–(5) into a single archive. Without this, every "optimisation" below is guesswork.

### 6.2 Ranked hypotheses

Ordered by expected impact on *this* device, with the signature that confirms each. Labels are hypotheses until measured.

| ID | Hypothesis | Expected signature | Conditional fix (only if confirmed) |
|---|---|---|---|
| P1 | **Shader/pipeline compilation stutter.** First sight of a material creates a Vulkan pipeline on a slow driver path; Mali DDK pipeline creation is expensive; DXVK may also be compiling SPIR-V. | Isolated long frames (50–500 ms) exactly when new surfaces/enemies appear; disappears on second visit in the same session. | Persistent **state cache** (`dxvk.stateCache`) + driver pipeline cache to app storage; pre-warm by rendering a hidden warm-up scene during loading; `dxvk.numCompilerThreads` sized to big cores; optional async pipeline compilation where the driver supports it. Never "shader stutter" hidden by lowering quality. |
| P2 | **CPU↔GPU synchronisation stalls.** Submit-and-wait per frame; queue depth 1; a blocking `vkQueueWaitIdle` or swapchain acquire with `maxFrameLatency` too low; the PanVK kbase path is explicitly *synchronous* (`[C]` LukeValen). | Render thread blocked inside submit/acquire; `gpuload` low while `frametimes` high; worse with more draw calls. | `d3d9.numBackBuffers=3`, `dxvk.maxFrameLatency=1`, avoid per-frame `WaitIdle` (fence-only), triple-buffered staging rings. On PanVK, expect this to dominate regardless (its sync path is correctness-first). |
| P3 | **Render-target / post-process bandwidth** (tile store/restore) — bloom, HDR, water reflections. | Frame time scales with resolution and with post-process toggles; GPU-bound with no CPU stall. | Reduce *intermediate* precision and pass count rather than the art: RGBA16F→RGBA8 for bloom chain, skip redundant clears, reuse targets, avoid full-surface copies. Optional user setting, default = current behaviour. |
| P4 | **Texture format conversion** (BCn→ASTC/ETC on a GPU without BC). | Long level loads, RAM peak, CPU spinning in the conversion on the loading screen; occasional freezes (BCn→ASTC has crashed devices, `[C]`). | Prefer a **BC-capable path** (PanVK on G52 decodes BC1–BC3) or a one-time cached transcode on disk; never convert per-frame; make conversion opt-in if it has crashed. |
| P5 | **Audio thread starvation & underruns** (see §7) causing the *whole game* to hitch because it is coupled to the audio clock or holds a mix lock during the sequencer. | Periodic 20–40 ms hiccups at buffer periodicity; audio crackle precedes the hitch. | Decouple: lock-free ring, decode/mix on its own thread with raised priority, no allocation/locking in callback, buffer of 2–3 periods at 48 kHz. |
| P6 | **Cutscene path** (see §7): CPU-side Bink decode + per-frame texture upload + no frame pacing. | 8–10 FPS in cutscenes, 25 FPS in gameplay (**matches the reported numbers exactly**); decode thread pegged; audio drifts. | Dedicated decode thread w/ NEON path, ring of 3 GPU textures, YUV→RGB on GPU, timestamp-driven presentation (§7). |
| P7 | **Draw-call submission overhead** (D3D9 → DXVK → Vulkan, plus possible per-draw state churn from a 2004 SM2.0 path). | CPU-bound with low GPU load; scales with object count; improves with `mat_picmip` but that's a quality change — reject. | Batch state changes, sort by pipeline, reduce redundant `SetRenderState`, avoid `UpdateSubresource` churn. Measure with the engine's own `r_speeds`-style counters if present. |
| P8 | **Present path / frame pacing**: vsync FIFO with a 25 FPS renderer, e.g. 25 → 20 (3 frames vs 2) judder; or blit-to-screen copy each frame. | Steady FPS but uneven frame *times*; visible hitching despite "good" average. | Choose present mode per device (`FIFO_RELAXED`/`MAILBOX` when available, else `FIFO`), `numBackBuffers` tuning, dedicated present thread, avoid full-surface copy. Frame-rate target setting drives this (30/40/60/unlimited). |
| P9 | **CPU/SoC thermals & scheduling**: G85 throttles; little cores carry render thread. | FPS decays over 10–15 min; `perfetto` shows runs on A55. | Set thread affinity to A75 for the render and audio threads, request `sustained performance` mode via the platform, avoid busy-wait spins. No visual cost. |
| P10 | **Container/emulation overhead** (only if Hypothesis A): Box64 translating SIMD-heavy engine code. | CPU-bound everywhere; profile shows dynarec blocks. | `BOX64_DYNAREC_BIGBLOCK=2-3`, `CALLRET=1`, `STRONGMEM` tuning, `WINEESYNC=1`. Never a visual change. |

### 6.3 The 25 FPS and 8–10 FPS numbers already tell us something

Gameplay 25 FPS vs cutscenes 8–10 FPS is a **2.5–3× drop**, far larger than the extra cost of drawing one fullscreen video quad. That is a strong, cheap inference: **[I]** the cutscene path is *not* just "one more quad" — it carries a per-frame CPU cost the gameplay path does not (Bink decode + likely a synchronous CPU→GPU texture upload, possibly with a format conversion), and P6 is therefore the highest-value single fix in the whole assignment. It also explains the audio drift: if presentation is frame-count-driven instead of clock-driven, a 3× slower video path is exactly where A/V sync breaks.

---

## 7. Audio and cutscene problems

### 7.1 What is actually involved **[C]**

* **Miles Sound System 6.5e** (`Mss32.dll`, `Mss32a.dll`, `vaudio_miles.dll`, `voice_miles.dll`) for music/SFX/dialogue.
* **Bink Video 1.6d** (`binkw32.dll`) for cutscenes and the intro/logo movies. Bink 1 carries its own audio codec; the game feeds video frames to a texture and audio to Miles/DirectSound.
* On Android the host side is **SDL3 audio** (`libSDL3.so`) → AAudio/OpenSL ES.

### 7.2 Mechanism → symptom table

| # | Mechanism | Symptom you would hear/see | Verify by | Fix |
|---|---|---|---|---|
| A1 | **Device opened at a rate the game does not use** (44.1 kHz content into a 48 kHz device, or vice versa) with a poor/late resampler, or `SDL_OpenAudioDevice` given the wrong `SDL_AudioSpec` and no resampling hint. | Everything is uniformly *slightly* slow/low or fast/high — matches "audio can become slightly slow". | Log the negotiated `SDL_AudioSpec.freq`, `format`, `samples`, and whether SDL reports a changed spec. Compare to the game's requested rate. | Let SDL resample (default) or resample explicitly with a decent quality setting; never open in "no conversion" mode. Pick **one** canonical rate (48 kHz) and resample at the boundary. |
| A2 | **Underruns from a too-small buffer or a callback that does work** (allocation, lock contention, mixing in the callback, calling into the guest under emulation). | Periodic crackle + short stalls; sound "catches up", feels desynchronised; worse in cutscenes. | Count callback shortfalls; measure callback duration p99 vs period; log underrun count per minute. | Ring buffer of 2–3 periods (e.g. 3×1024 frames @48 kHz ≈ 64 ms), mix on a dedicated thread, callback does **memcpy only**. Lock-free SPSC ring. |
| A3 | **Audio clock vs video clock drift**: presentation paced by *video frame count* instead of by the audio device clock / Bink timestamps. | Drift *accumulates* — fine for 5 s, seconds off after 2 minutes. | Log (audio frames consumed) − (video timestamp) every second; a linear ramp confirms it. | Drive video presentation from the **audio clock** (or Bink's own timestamp) with a small hysteresis window (±1 frame) and drop/duplicate video frames, never resample audio on the fly. |
| A4 | **Thread priority / scheduling** on a 2×A75 SoC: audio thread on a little core behind the render thread and the decoder. | Worse when the scene is busy; dropouts correlate with render spikes. | `perfetto` scheduling trace. | Raise audio + decode thread priority (`THREAD_PRIORITY_URGENT_AUDIO` equivalent), pin to A75, keep the render thread off the audio core if there are only 2 big cores. |
| A5 | **Video decode on the render thread**, so any decode spike delays the audio-feeding code and the frame. | Cutscene FPS drop *and* audio stutter together. | Timeline correlation decode vs underrun. | Decode on its own thread, 2–3 frame lookahead, never block the mixer. |
| A6 | **Per-frame texture creation/upload for video** (e.g. `CreateTexture` per frame, or CPU YUV→RGB). | 8–10 FPS with a hot CPU and a lot of bandwidth; frame time ∝ resolution. | Count texture creations/s; check upload size per frame. | Ring of 3 pre-allocated GPU textures; YUV→RGB in a fragment shader; upload once per frame with a fence; no allocations in the loop. |
| A7 | **Emulated Bink/Miles** (Hypothesis A only): x86 SIMD decoding inside Box64. | CPU pegged on one core during cutscenes; decode alone costs more than the frame budget. | Profile symbol attribution. | Rewrite the Bink **audio** path to native (small, contained) and keep only video emulated, or decode video natively from the `.bik` container (Bink 1 is documented enough for a decoder, and FFmpeg's `bink` decoder is a permissively licensed reference), or pre-transcode cutscenes to a format the port can play (asset *derivative* of user-owned data — stays off GitHub). |

### 7.3 Order of work

A1 and A3 are cheap to verify and are the two most common real causes of "slightly slow, drifts during cutscenes". A2 is the most common cause of "crackles when busy". A6 explains the FPS collapse. I will instrument first (§6.1), then fix A1/A3/A2 together, then A5/A6, because A6 without A5 just moves the stall.

**Non-negotiables from the assignment:** original voice/music/SFX preserved bit-exactly; no time-stretching, no pitch shifting, no "speed up the audio to catch up".

---

## 8. Touch-input architecture and identified bugs

### 8.1 The chain

```
Android MotionEvent (multi-pointer)
  → SDL3 (touch → mouse/pointer events, or raw touch via SDL_EVENT_FINGER_*)
  → port's input bridge (decides: mouse-look deltas, key events, virtual gamepad)
  → engine (Source 2004 uses DirectInput for mouse/keyboard; via Wine or a native shim)
  → game action
```

Each arrow is a place where input can be lost, delayed, or double-applied. Two structural risks:

* **R1 — Synthetic mouse for camera look.** Android cannot warp the cursor. Any path that relies on `SetCursorPos` + absolute position (natural for a 2004 DirectInput game) will stall at the screen edge or fight the touchscreen. Correct approach: relative deltas per pointer with the look pointer tracked by `pointerId`, and a "virtual cursor" only where the UI genuinely needs absolute pointing.
* **R2 — Single-pointer assumptions.** `MotionEvent` with `ACTION_POINTER_DOWN/UP` must be routed per pointer, and `ACTION_MOVE` must be split per pointer index. A joystick + look + two buttons must be simultaneously usable; any implementation that reads `event.getX()` (index 0) will drop or teleport one of them.

### 8.2 Bugs the assignment reports, mapped to likely causes

| Reported problem | Likely cause | Verification |
|---|---|---|
| "Some important controls are missing or poorly positioned" | No cutscene skip/advance affordance; menu/journal/inventory possibly mapped to a chord or unreachable while the look finger is down. | Checklist every engine action against the touch map, in-game. |
| "Actions that currently require pressing multiple buttons when one should be sufficient" | The bridge emits a *chord* (e.g. hold-modifier + button) because the engine action lives behind a modifier, or a key is sent on tap and released late, so the game sees two presses. | Log every injected keycode/timestamp from the bridge; compare to what the game needs. Typical Bloodlines needs are single keys: use/interact, jump, crouch, weapon next/prev, inventory, journal, character sheet, map, feed/discipline, skip cutscene. |
| "Missing left-side cutscene skip/advance control" | Skip is usually `ESC` in Source-era titles (often with a confirm), or a click on the dialogue panel. If the engine accepts `ESC`, a single left-side "Skip" button sending one `ESC` key-down/up is sufficient; no mouse needed. | Try `ESC` during a cutscene with a hardware keyboard/`adb shell input keyevent 111`. If it skips, mapping is trivially supported — **so investigate before designing around it**. If the engine instead listens for a mouse click on the "continue" panel, the button must inject a click at a fixed screen coordinate. |
| Touch interfering with camera movement | Pointer routing (R1/R2) or a HUD control catching the look pointer. | Drag look while holding joystick + pressing a combat button; check all three survive. |

I will not claim the skip key is `ESC` on this build — that is a **device test**, and it is step one of the control work.

### 8.3 What already exists must be preserved

Per the assignment, existing working mappings are the source of truth. So the HUD work is architected as **presentation + layout + persistence layered over an unchanged action set**:

```
HudAction (enum, stable ids)  ←──  existing bridge mapping (untouched)
      │
HudLayout (positions, sizes, opacity, joystick radius/sensitivity, per-aspect overrides)
      │
HudOverlayView (Android Canvas rendering, pointer routing, haptics, pressed states)
      │
InputSink (interface)  →  existing input bridge (keycodes / relative mouse deltas)
```

`InputSink` is the seam. The HUD never decides *what* a button does; it reports "action `Interact` pressed/released at t". If the existing bridge uses a chord for something, that stays — and gets a single stable id — so the visual redesign cannot break gameplay. Bugs are then fixed *in the bridge*, deliberately, one at a time, each with a test.

---

## 9. Proposed HUD architecture (assignment §6 A–F)

**Design language:** dark, gothic, restrained — a thin bone/ash stroke with a low-alpha fill, one accent colour (blood red) used only for active/selected states, no gradients except a subtle inner shadow, no oversized circles. Buttons sized in dp and clamped to a fraction of the short edge so the same layout works on 16:9 → 20:9.

**Zones (landscape):**

```
┌───────────────────────────────────────────────────────────────────┐
│ [≡ menu]      health/blood                              [diag]    │
│                                                                    │
│   ╭─────╮                                     ╭────────────╮       │
│   │  ◎  │  ← movement stick             look │  (free area)│      │
│   │     │     + sprint/crouch/jump           ╰────────────╯       │
│   ╰─────╯                          ╭────╮  ╭────╮  ╭────╮          │
│                                    │ 🔥 │  │ ⚔  │  │ ✋ │  ← combat│
│  ╭──────────╮                      ╰────╯  ╰────╯  ╰────╯          │
│  │inv jrnl │  ← secondary cluster  ╭──────╮                        │
│  │chr  map │                       │SKIP ▸│ ← cutscene only        │
│  ╰──────────╯                      ╰──────╯                        │
└───────────────────────────────────────────────────────────────────┘
```

* Left: joystick (drag anywhere in the left third to engage; base follows the first touch point so the thumb never has to find a fixed circle), sprint/crouch/jump.
* Right: full-height look area with a deadzone ring so a stray brush does not jerk the camera; combat cluster (primary attack, discipline/feed, interact) reachable by the right thumb; distinct shape/colour per family (combat = circle-free "blade" hexagon, interaction = rounded square, meta = small square).
* Secondary cluster (inventory, journal, character, map) in the left-bottom corner, deliberately **away** from combat, small, with a long-press to hide.
* Cutscene-only overlay: a single "Skip ▸" pill on the left, appears only when the runtime reports a cutscene state (if such a signal exists; otherwise it is a user-toggleable always-on button).
* Safe areas: everything inset by `WindowInsets.systemBars` + display cutout; the editor previews the same insets.
* Customisation: drag (with snap to grid + "reset"), per-button scale, global transparency, joystick radius + sensitivity (sensitivity as a curve exponent, not just a multiplier), rename-free but per-aspect layouts (a 16:9 layout and a 20:9 layout can differ), export/import to JSON, restore defaults.
* Feedback: 80 ms pressed transition, 1 px inset, accent ring on active, `HapticFeedbackConstants.CONTEXT_CLICK` on press (toggleable), selection ring on active weapon/item. No per-frame animation when idle — the HUD must cost ~0 GPU when idle since the game shares the GPU.

**Tests:**
* Geometry/layout resolution for 16:9, 18:9, 19.5:9, 20:9 plus insets and dp densities — pure functions, unit-testable without a device.
* Persistence round-trip and forward/backward compatibility of the layout JSON.
* Hit-testing: no overlapping actionable rects; each action reachable; control zones do not intersect the declared "keep-clear" centre rect.
* Pointer routing: synthetic multi-touch sequences proving joystick+look+button simultaneity.

This is the one area of the assignment I can build and test end-to-end **without** the port, which is why the repo now starts here (see `hud/`), with `InputSink` as the integration seam.

---

## 10. Proposed implementation plan

Small, independently testable assignments. Each one keeps the tree buildable.

| # | Assignment | Deliverable | Acceptance criteria | Depends on |
|---|---|---|---|---|
| **002** | Evidence capture & architecture lock-in | `tools/device-probe.sh` + a filled `docs/reference/runtime-profile.md` | §2.5 D1–D5 answered with real output; one hypothesis retired | device/APK |
| **003** | Performance instrumentation | in-app frame-time + stutter ring buffer, HUD toggle, log export | p50/p95/p99 and per-subsystem breakdown; < 0.3 ms overhead | 002 |
| **004** | HUD foundation + preview/customiser app | `hud/` module: layout model, overlay view, editor, persistence, tests, CI APK | All 6 aspect ratios correct; multi-touch test passes; mappings preserved 1:1 | none (in progress) |
| **005** | Audio & cutscene correctness | clock-driven presentation, ring textures, decode/mix threads, diagnostics | drift < 20 ms over a 3-min cutscene; zero underruns at p99; no pitch change | 003 |
| **006** | Frame pacing & sync tuning | back-buffer/queue-depth/present-mode settings, state cache, pre-warm | p99 frame time down ≥ 25 % on the target device; no visual change | 003 |
| **007** | Optional PanVK backend | driver probe, ICD packaging (source-built, licensed), settings + fallback, quality report | game still launches and plays with a missing/broken driver; artefacts documented | 002, 006 |

The order is deliberate: measurement before optimisation, correctness before performance, and the experimental driver last — so a PanVK failure can never be the reason the game does not launch.

### 10.1 Build & CI

`.github/workflows/android-build.yml` builds the HUD module and runs its unit tests on every push (Ubuntu runner, JDK 17, Android SDK, Gradle). Artifacts: debug APK + test report. This also serves as the project's proof that the tree builds, since the interactive sandbox has no Android toolchain (§1.3).

---

## 11. Risks and fallback strategies

| Risk | Impact | Mitigation / fallback |
|---|---|---|
| Port sources never arrive | Assignment stalls after §4 feasibility work | Standalone HUD + tooling remains fully useful; the `InputSink` seam makes later integration additive |
| PanVK crashes or renders wrong (sky artefacts, feature lying) | Quality regression, possible non-launch | Off by default; automatic fallback to current path on `vkCreateDevice` failure or first N frames of faults; explicit "restore stock driver" action; never the only backend |
| Kbase UAPI mismatch on the TECNO kernel | PanVK cannot create a device | `PANVK_DEBUG=startup` diagnostics surfaced in the settings screen; driver selection refuses to activate on a failed probe |
| BCn→ASTC conversion freeze (known crash class) | Device reboot | Conversion disabled by default; documented as unsafe |
| Optimisations regress the working game | Wasted effort, user-facing breakage | Every change behind a setting or a measured win; measurement gate before/after; preserve original visuals by default |
| Legal/licensing | Repo takedown | No game assets, no APKs, no proprietary blobs in git; driver builds from source with notices; user-supplied data stays on-device |
| No device access in CI | Untestable performance claims | Perf work is validated on the user's device with a documented protocol; CI only proves build + unit tests |

---

## Appendix A — Device probe commands (no root)

Implemented in `tools/device-probe.sh`; the essentials:

```bash
# Platform + GPU
adb shell getprop ro.product.model; adb shell getprop ro.board.platform
adb shell getprop ro.build.version.release; adb shell getprop ro.product.cpu.abilist
adb shell dumpsys SurfaceFlinger | grep -iE 'GLES|Vulkan' | head -20

# Vulkan: which ICD does the system expose, and which does the app map?
adb shell ls -l /vendor/lib64/hw/ /vendor/lib/hw/ | grep -i vulkan
adb shell 'cat /proc/$(pidof <pkg>)/maps 2>/dev/null | grep -iE "vulkan|GLES|dxvk|d3d9"'

# What is in the APK
adb shell pm path <pkg>
adb pull <base.apk>
unzip -l base.apk | grep -E 'lib/arm64-v8a/|assets/(wine|box64|dxvk|graphics)'
unzip -p base.apk AndroidManifest.xml | strings | head -40

# kbase / PanVK feasibility
adb shell 'ls -l /dev/mali0'
adb shell 'cat /sys/kernel/debug/mali0/version 2>/dev/null || echo "no access without root"'
# after installing PanPlay/panprobe: run its self-test and record driverInfo

# Frame pacing / scheduling (debuggable builds)
adb shell atrace -t 10 gfx view sched freq idle -a <pkg> > trace.atrace
adb shell dumpsys gfxinfo <pkg> > gfxinfo.txt
```

## Appendix B — Sources consulted

1. `zenithblue-oss/panvk-kbase-android` — README, releases (`panvk-kbase-v26.3.1`), `docs/MALI-GPU-ARCHITECTURES.md` (Mali arch/PanVK support matrix; G52 MC2 test results; BC1–BC3 decode on G52; DXVK/zink requirements; D3D9 −26 % G52 note).
2. `JimVulkan/mali-panvk` — README (kbase, no root, no `/dev/dri`; supported GPUs incl. G52; gralloc/`libui` handling; AdrenoTools/Winlator/Bannerlator compatibility; runtime switches; licensing).
3. `LukeValen/panvk-mali-g52` — README (MT6769/G52 r1 MC2/Android 14/kbase 11.38 reference; Winlator/Ludashi WSI; DXVK D3D9/10/11 basic rendering; force-advertised features; correctness-first sync path).
4. Mesa Panfrost/PanVK documentation — conformance status (G52 GLES conformant; PanVK conformant only on G610; Bifrost experimental, env-gated).
5. Khronos "Architecture of the Vulkan Loader Interfaces" — ICD discovery, `VK_ICD_FILENAMES`, and the explicit statement that Android's loader uses `hw_get_module("vulkan")` and cannot be redirected normally.
6. Community PanVK-for-DXVK device/version matrices (FristOneRR and forks) — Helio G85/G52 MC2 reported working; DXVK version ceilings on Bifrost (1.x / ≤2.4.1; 2.5+ fails); known DX9 sky artefacts; BCn→ASTC freeze warning.
7. PCGamingWiki, *Vampire: The Masquerade – Bloodlines* — engine Source 2004, Direct3D 9, Miles Sound System 6.5e, Bink Video 1.6d, shipped DLL inventory.
8. DXVK issue #3321 and DXVK-Native documentation — `libdxvk_d3d9.so` naming and per-WSI (SDL2/GLFW/SDL3) ABI split.
9. Winlator upstream documentation/changelogs (brunodev85) and Gladio (`brunodev85/gladio`) — container architecture (Wine + Box64 + XServer), Gladio as OpenGL-over-GLES for Mali, Vortek, BCn emulation and Mali-specific fixes in v10/v11.
10. This repository's git history and the GitHub REST API (repo size, branches, tree contents, workflows) — the empty-repository finding.

## Appendix C — Explicit unknowns

1. Which architecture (§2.2 vs §2.3) is shipped.
2. The responsibility of `libd3d9_gles.so`.
3. Whether the port's Vulkan entry point is the system loader or an app-bundled one (§4.4) — **the single gate on PanVK**.
4. Whether the working render path is Vulkan at all (§4.7).
5. The engine's cutscene skip input, and the true action list behind the existing touch buttons (§8.2).
6. The negotiation parameters of the SDL audio device in use (§7.2 A1).
7. kbase UAPI version on the TECNO unit.
8. Whether gameplay is CPU- or GPU-bound at 25 FPS (§6.2 P1–P10).

---

## 12. Assignment 001 wrap-up

### 12.1 Files inspected

| Path | Result |
|---|---|
| whole repository tree (`git ls-tree -r main`), all refs, `git fsck`, stashes | one file: `README.md` (30 bytes) |
| GitHub API for the repo | `size: 0`, 1 commit, no workflows (404), no releases, no issues |
| upstream projects (READMEs, release notes, docs), Mesa/Panfrost docs, Khronos loader docs, PCGamingWiki, DXVK issue tracker, Winlator/Gladio docs | see Appendix B |

No APK, no `.so`, no exported-symbol dump and no prior research report existed to inspect. That is
the single most important finding of this assignment (see §0.1).

### 12.2 Files created

| Path | Purpose |
|---|---|
| `docs/reports/001-technical-investigation.md` | this report |
| `docs/README.md`, `docs/reference/build.md`, `docs/reference/runtime-profile.md` | documentation index, toolchain, evidence template |
| `tools/device-probe.sh` | no-root adb evidence collection for D1–D7 |
| `tools/ci-annotate.sh` | surfaces build diagnostics as a GitHub annotation (the raw job log is served from a host this environment cannot reach) |
| `.github/workflows/android-build.yml` | unit tests + debug APK on every push |
| `app/…` (9 Kotlin sources, 2 test suites) | HUD foundation: layout model, geometry, persistence, overlay view, preview/customiser |
| `README.md` | rewritten project front page with the blocker and status stated plainly |

### 12.3 Build and test results

Verified by GitHub Actions run `42764980115` (branch `arena/09d766de-bloodlines-android-rebuild`):

| Step | Result |
|---|---|
| `gradle :app:testDebugUnitTest` | **25 tests, 0 failures** |
| `gradle :app:assembleDebug` | **success** (debug APK produced; artifact `hud-build-output`) |
| toolchain | JDK 17, Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 26 |

Two earlier runs failed and were fixed rather than worked around:

1. `RectF.centerX/centerY` are functions, not properties (compile error).
2. Two failing tests, one of which exposed a genuine design flaw: a pure power curve with a 1 px
   reference *amplifies* small look movements instead of damping them. The curve is now relative to
   `LOOK_REFERENCE_DP = 24dp` (damped below, amplified above, exactly linear at exponent 1), and the
   stick test's full-deflection input was corrected.

The layout maths was additionally validated numerically before implementation by replicating the
Kotlin exactly in a script and sweeping 16:9, 18:9, 19.5:9, 20:9, small-phone and tablet dp sizes,
with and without display-cutout insets. That sweep is what caught the real unit bug in the first
implementation (dp offsets multiplied by the compaction factor but not by density, so offsets were
treated as pixels and every control crowded into its corner).

### 12.4 What this assignment deliberately did **not** do

* No changes to graphics, audio or input behaviour of the port — the port is not present.
* No PanVK integration, no driver binaries, no quality reductions, no resolution scaling.
* No exposure of any setting that is not implemented: the preview app's sliders control only what
  the HUD actually implements (opacity, size, stick radius, look sensitivity/precision, haptics,
  skip-button visibility).

### 12.5 Known issues

1. **Port absent** — the blocker; everything else is downstream of it.
2. **HUD not yet embedded in the game.** `InputSink` is the seam; integration is a few lines but
   must happen against the real input bridge, and the bridge's current mappings must be treated as
   authoritative (report §8.3).
3. **Extreme customisation can overlap controls.** At maximum button scale the stick ring can reach
   the sprint button. `HudGeometry.validate` detects it and the preview app reports the count and the
   first problem in its status line, but the editor does not yet prevent the drag. Next step: block
   the drop, or auto-shrink.
4. **No weapon/item selection indicator.** It needs game state (which weapon is active); the HUD has
   no way to know yet. Designed for, not built.
5. **Cutscene skip visibility is a manual toggle** until the runtime reports a cutscene state. The
   skip action itself is a single stable `HudAction` waiting for the correct input binding, which
   must be established on the device (report §8.2).
6. **No UI screenshot tests.** CI has no device/emulator; geometry is covered by pure functions, but
   rendering regressions would need a Robolectric or emulator stage.
7. **Haptics are untuned** (platform constants, not measured intensities).

### 12.6 Next recommended assignment

**Assignment 002 — evidence capture and architecture lock-in.** Provide the APK (or its
`lib/arm64-v8a/` slice) and/or the port source tree, then run `tools/device-probe.sh` on the TECNO.
Fill `docs/reference/runtime-profile.md` from its output. Exit criteria: D1–D7 answered, one
hypothesis retired, and the answer to §4.4 (does this runtime own its Vulkan loader?) recorded.

If the APK cannot be shared, the recommended parallel track is **Assignment 004b — HUD completion
against a stand-in**: finish the editor (drag prevention, per-button enable/hide UI), add the
active-weapon indicator fed by a host-provided callback, and add an emulator screenshot test. That
work is independent of the port and survives integration unchanged.
