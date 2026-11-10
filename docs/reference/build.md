# Build notes

## Why there is no `gradlew` in this repository

The Gradle wrapper JAR is a binary that must be produced by a Gradle installation. The environment
this repository's foundation was authored in has no JDK, no Android SDK and no access to Google
Maven, so the wrapper could not be generated locally (and committing a hand-made binary would be
worse than not committing one).

Consequences:

* CI (`.github/workflows/android-build.yml`) provisions Gradle itself with
  `gradle/actions/setup-gradle` and runs `gradle` directly, so the build is reproducible without the
  wrapper.
* On a developer machine with a JDK, generate the wrapper once and commit it:
  ```sh
  gradle wrapper --gradle-version 8.9
  ./gradlew :app:assembleDebug
  ```
  After that, replace `gradle` with `./gradlew` in CI if preferred.

## Toolchain

| Component | Version |
|---|---|
| Gradle | 8.9 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| NDK | not required by the HUD module |

## Module layout

```
app/src/main/kotlin/dev/bloodlines/hud/
  HudAction.kt        stable control identities (never key codes)
  HudLayout.kt        layout model + shipped defaults
  HudGeometry.kt      pure layout maths (JVM-testable, no Android types)
  HudLayoutCodec.kt   versioned text persistence format
  HudPalette.kt       colours/metrics (plain ints, JVM-testable)
  InputSink.kt        the seam to the port's input bridge
  LayoutStore.kt      per-aspect SharedPreferences storage
  HudOverlayView.kt   transparent touch HUD (Canvas, no per-frame allocation)
  MainActivity.kt     standalone preview + layout customiser
app/src/test/kotlin/dev/bloodlines/hud/
  HudGeometryTest.kt      geometry, safe areas, aspect ratios, stick/look maths
  HudLayoutCodecTest.kt   round-trip, forward compatibility, clamping
```

No AndroidX, no Compose, no third-party runtime dependency: the only test dependency is JUnit 4.

## Commands

```sh
gradle :app:testDebugUnitTest     # pure-JVM tests, no device needed
gradle :app:assembleDebug         # debug APK
```

## Notes on the HUD module's place in the port

`HudOverlayView` is a normal `View`. To embed it in the port:

1. Add it as a sibling of the `SurfaceView`/`GLSurfaceView` (or above the SDL `SurfaceView`) in the
   activity's view hierarchy.
2. Implement `InputSink` over the existing input bridge — `onAction` maps a `HudAction` to whatever
   the bridge already sends (key codes, virtual gamepad, relative mouse). Do not change the bridge's
   mappings as part of this work.
3. Call `cutsceneActive = true/false` when the runtime reports a cutscene, if such a signal exists.
   If it does not, leave the skip button always visible (the layout supports both).
4. Keep the HUD out of the render loop: it draws on the normal Android view pass, never inside the
   game's GL/Vulkan context.
