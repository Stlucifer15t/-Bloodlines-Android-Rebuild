# Runtime profile — to be filled from `tools/device-probe.sh`

This file exists so the open questions in
[`docs/reports/001-technical-investigation.md`](../reports/001-technical-investigation.md) can be
closed with recorded evidence rather than assumptions. Until the rows below are filled, **do not**
implement driver/backend changes that depend on them.

## Device

| Field | Value | Source |
|---|---|---|
| Model / SoC | | `getprop ro.product.model` / `ro.board.platform` |
| Android / API | | `ro.build.version.release` / `sdk` |
| Kernel | | `uname -a` |
| kbase UAPI | | `/sys/kernel/debug/mali0/version` or `PANVK_DEBUG=startup` |
| GPU | Mali-G52 MC2 (Bifrost v7, JM) — expected | `dumpsys SurfaceFlinger` |
| `/dev/mali0` accessible | | `ls -l /dev/mali0` |
| Vulkan HAL(s) | | `ls /vendor/lib64/hw/ \| grep vulkan` |

## Architecture (D1–D5)

| Question | Answer | Evidence file |
|---|---|---|
| D1 Wine/Box64 assets in APK? | | `apk-contents.txt` |
| D2 Native libs present | | `apk-contents.txt` |
| D3 Guest process tree while running | | `report.txt` §4 |
| D4 Which Vulkan/GLES libs are mapped | | `maps-vulkan.txt` |
| D5 `libd3d9_gles.so` role | | `symbols-libd3d9_gles.so.txt` |
| D6 Custom ICD loadable? (loader owned by app?) | | `maps-vulkan.txt` |
| Conclusion: Hypothesis A (container) or B (native port) | | |

## Performance baseline (before any optimisation)

| Scenario | FPS | Frame time p50 / p95 / p99 (ms) | CPU- or GPU-bound | Notes |
|---|---|---|---|---|
| Gameplay, indoor | | | | |
| Gameplay, street (many objects) | | | | |
| Combat | | | | |
| Cutscene (video) | | | | |
| Cutscene (in-engine dialogue) | | | | |

## Audio

| Field | Value |
|---|---|
| Device sample rate / format / buffer actually opened | |
| Requested vs negotiated `SDL_AudioSpec` | |
| Underrun count over 3 min of cutscene | |
| Measured A/V drift after 3 min | |

## Touch controls

| Action | Current mapping (bridge) | Works? | Notes |
|---|---|---|---|
| Move | | | |
| Look | | | |
| Attack | | | |
| Interact | | | |
| Jump | | | |
| Crouch | | | |
| Inventory / Journal / Character / Map | | | |
| Weapon switch | | | |
| Skip cutscene | | | engine's own skip input: ? |
