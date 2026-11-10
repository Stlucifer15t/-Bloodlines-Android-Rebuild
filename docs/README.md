# Documentation

| Document | Purpose |
|---|---|
| [`reports/001-technical-investigation.md`](reports/001-technical-investigation.md) | **Assignment 001 deliverable.** Runtime architecture, rendering pipeline, PanVK feasibility (Q1–Q7), Mali-G52 constraints, bottleneck hypotheses, audio/cutscene analysis, touch-input findings, implementation plan, risks. Every claim labelled Confirmed / Inferred / Hypothesis / Unverified. |
| [`reference/runtime-profile.md`](reference/runtime-profile.md) | Fill-in template that closes the report's open questions using `tools/device-probe.sh` output. |
| [`reference/build.md`](reference/build.md) | Toolchain, module layout, why there is no Gradle wrapper, how the HUD embeds in the port. |

## Reading order

1. Report §0.1 — the repository does not yet contain the port. Everything downstream depends on
   resolving that (`tools/device-probe.sh` + the APK).
2. Report §4.4 / §4.7 — whether PanVK can even be loaded is a property of *this* runtime, not of the
   driver. Do not plan around PanVK before D4/D6 are answered.
3. Report §6.1 — measurement before optimisation.
4. Report §10 — assignment sequence.

## Conventions

* **Confirmed / Inferred / Hypothesis / Unverified** labels carry the same meaning throughout.
* Never commit game assets, APKs, or third-party proprietary binaries.
* Third-party open-source components are built from source (pinned commit) with their licence and
  NOTICE files retained; checksummed prebuilt driver packages may only be loaded at the user's
  explicit action, never silently.
* The tree must build after every change; CI is the proof (`Android build` workflow).
