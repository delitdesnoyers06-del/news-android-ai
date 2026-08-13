# Phase 0 — results

Groundwork + spikes for the on-device AI port (docs/ai/PLAN.md §5 Phase 0). Everything below was
executed, not predicted.

Toolchain used: JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`, matches CI), Android SDK at
`/opt/android-sdk` (platform-tools, platforms;android-36, build-tools;36.0.0), licences accepted.
`local.properties` holds `sdk.dir=/opt/android-sdk`.

---

## What changed

| File | Change |
|---|---|
| `gradle.properties` | `ANDROID_BUILD_MIN_SDK_VERSION` 21 → **24** |
| `News-Android-App/build.gradle` | second flavor dimension `ml` = `mlNone` \| `mlGemma`; `abiFilters`; AI dependencies on `mlGemmaImplementation`; `packagingOptions` excludes |
| `News-Android-App/proguard-rules.pro` | +59 lines of LiteRT-LM / MediaPipe / Gson keep rules |
| `src/main/AndroidManifest.xml` | `FOREGROUND_SERVICE_DATA_SYNC` permission; `foregroundServiceType="dataSync"` on `DownloadWebPageService` |
| `src/mlGemma/AndroidManifest.xml` | new — `uses-native-library` for `libvndksupport.so` / `libOpenCL.so` |
| `src/{mlNone,mlGemma}/…/di/AiModule.java` | new — empty Dagger module at the same FQN in both flavors |
| `src/main/…/di/AppComponent.java`, `src/androidTest/…/di/TestComponent.java` | `+ AiModule.class` |
| `src/{ossMlGemma,devMlGemma}/res/values/strings.xml` | new — per-variant `authority` / `account_type` / `app_name` |
| `src/test/resources/org.robolectric.Config.properties` | dead `emulateSdk=18` → `sdk=34` |
| `.github/workflows/{ci,codeql}.yml` | variant-pinned tasks and artifact paths |

Two pre-existing bugs fixed in passing:
- `DownloadWebPageService` had no `foregroundServiceType`, so `startForeground` at
  `DownloadWebPageService.java:84` already threw `MissingForegroundServiceTypeException` on
  targetSdk 34+ — i.e. the web-archive feature was already broken, independent of this work.
- `org.robolectric.Config.properties` used `emulateSdk`, removed from Robolectric years ago; the
  value was silently ignored and was below minSdk anyway.

## Verification — all green

```
./gradlew :News-Android-App:dependencies --configuration ossMlGemmaDebugRuntimeClasspath
./gradlew :News-Android-App:assembleOssMlNoneDebug :News-Android-App:assembleDevMlGemmaDebug
./gradlew :News-Android-App:assembleOssMlGemmaRelease            # R8 full mode
./gradlew :News-Android-App:lint :News-Android-App:assembleDevMlNoneDebug \
          :News-Android-App:assembleDevMlGemmaDebug
./gradlew :News-Android-App:testOssMlNoneDebugUnitTest detekt spotlessCheck
```

| Invariant | Measured |
|---|---|
| mlNone native libs | **0** — no `.so` in the APK at all |
| mlNone litertlm/mediapipe references | **0** |
| mlGemma ABIs | `arm64-v8a`, `x86_64` only — no `armeabi-v7a`, no `x86` |
| Clearcut `datatransport` in mlGemma | **0** — the `exclude group:` holds through packaging |
| `libmediapipe_tasks_textgenai_jni.so` | excluded; only `libmediapipe_tasks_jni.so` ships |
| R8 full mode | `minifyOssMlGemmaReleaseWithR8` succeeds |
| `lint` (`abortOnError true`) | passes |
| `detekt` (`maxIssues: 0`), `spotlessCheck` | pass |
| `testOssMlNoneDebugUnitTest` | passes |

APK sizes:

| Variant | Bytes |
|---|---|
| `dev` (pre-change baseline, minSdk 21) | 13,918,569 |
| `devMlNone` | 13,444,099 |
| `devMlGemma` (both ABIs, debug) | 90,344,458 |

`devMlNone` is 474 KB **smaller** than the baseline. That is the minSdk bump, not the flavor split:
the dex layout is identical (17 dex files, none dropped) and the shrinkage is concentrated in
`classes16.dex` (2,435,728 → 1,720,396 B), the core-library-desugaring output, which has less to
backport at API 24.

`devMlGemma` at 90 MB carries **both** ABIs. x86_64 accounts for 38.9 MB of native code, so an
arm64-only artifact is ≈51 MB. This is above the plan's 36–40 MB estimate — revise that number.
Consider an ABI split for release.

## Spike results

| Spike | Status | Result |
|---|---|---|
| **S0** dependency resolution | **RESOLVED** | Clean. `gson` unifies at 2.13.2 (Retrofit's 2.13.1 upgrades, no conflict), `kotlin-reflect` 1.8.22 → 2.2.21 via constraint, `kotlinx-coroutines-android` 1.6.4/1.8.1 → 1.9.0. **`ext.kotlin_version` did NOT need bumping** — the plan's conditional 2.2.10 → 2.2.21 change was not applied because resolution and both compiles succeed without it. Leave it alone. |
| **S0a** work-runtime version | **CORRECTED** | The plan's `androidx.work:work-runtime:2.10.6` **does not exist** — the 2.10 line stops at 2.10.5. Pinned **2.10.5**. |
| **S9** provider authority collision | **RESOLVED** | Real, and worse than the plan described. `authority`/`account_type` are hardcoded strings, and `src/dev/res` (dimension `default`, declared first) outranks `src/mlGemma/res`, so `devMlGemma` would have inherited authority `…dev` against applicationId `…dev.ai`. Fixed with combined-flavor source sets. Verified in the built APKs: `devMlNone` → `de.luhmer.owncloudnewsreader.dev.provider`, `devMlGemma` → `de.luhmer.owncloudnewsreader.dev.ai.provider`. Both installable side by side. |
| **S7** R8 keep rules | **PARTIAL** | The release build compiles and R8 completes. This does **not** prove the keep rules are correct — R8 stripping a JNI-reachable class is a *runtime* failure. Still needs an on-device smoke run before the first AI PR is done. |
| S1 RAM / tok-s per model | **OPEN** | Needs a physical device. |
| S2 constrained decoding on E2B | **OPEN** | Needs a device + a downloaded model. |
| S3 GPU delegate for TextEmbedder | **OPEN** | Needs a device. Shipped default is CPU regardless. |
| S4 embed prefix applied once vs twice | **OPEN** | Needs a device. **Must run before any user vector is written** — getting it wrong silently degrades the taste model with no error. |
| S5 tasks-text without datatransport | **PARTIAL** | Packaging confirmed clean; `TextEmbedder.createFromOptions` not yet called, so `NoClassDefFoundError` at init is still possible. Needs a device. |
| S6 first-load wall time | **OPEN** | Needs a device + the 2.588 GB model. |
| S8 systemInstruction re-prefill | **OPEN** | Informational only. |

Every remaining spike needs physical hardware. Nothing that can be settled on a build machine is
still open.

## Not done in Phase 0 (deliberate)

`AppComponent`/`TestComponent` got the `AiModule` wiring but **no `injectXxx()` stubs** — those
name classes that do not exist until Phase 1, and empty stubs would not compile. `AiModule` is
empty in both flavors for the same reason; the point of creating it now was to prove the
two-source-set arrangement builds, which it does.
