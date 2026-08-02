# HARD FACTS — measured directly, not from docs. These OVERRIDE any doc-derived claim.

Established by downloading and unzipping the real artifacts on 2026-08-01.

## LiteRT-LM Android artifact

Published versions (from https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/group-index.xml):
```
litertlm-android: 0.0.0-alpha06, 0.8.0, 0.9.0-alpha01..06, 0.9.0-beta, 0.9.0,
                  0.10.0, 0.10.2, 0.11.0-rc1, 0.11.0, 0.12.0, 0.13.0, 0.13.1, 0.14.0, 0.15.0
litertlm-jvm:     same list
```
=> **PIN `com.google.ai.edge.litertlm:litertlm-android:0.15.0`.** Do NOT use `latest.release`
   (dynamic versions break reproducible builds and this project has dependency verification configured:
   `org.gradle.dependency.verification.console=verbose` in gradle.properties).

### From `litertlm-android-0.15.0.aar` (19,827,303 bytes)

**AndroidManifest.xml inside the AAR:**
```xml
package="com.google.ai.edge.litertlm"
<uses-sdk android:minSdkVersion="24" />
```
=> **minSdk 24.** The app is currently 21 (`gradle.properties:ANDROID_BUILD_MIN_SDK_VERSION=21`).
   The bump is 21 -> 24 (Android 7.0 Nougat). This is a small bump, NOT 26+.

**Native libraries shipped — THIS IS THE BIG ONE:**
```
jni/arm64-v8a/liblitertlm_jni.so    21 MB
jni/x86_64/liblitertlm_jni.so       25 MB
```
=> **ONLY arm64-v8a and x86_64. There is NO armeabi-v7a and NO x86.**
   32-bit ARM devices CANNOT run this feature at all — not "slowly", not at all.
   x86_64 exists only for emulators.
   => `abiFilters` policy: ship `arm64-v8a` (real devices) + `x86_64` (emulator/CI) and
      gate the whole feature on `Build.SUPPORTED_ABIS` containing arm64-v8a.
   => 46 MB of uncompressed native code; ~19.8 MB of AAR. An ABI split or a
      `abiFilters 'arm64-v8a'` release keeps this to ~21 MB.

**Transitive dependencies (from the .pom):**
```
com.google.code.gson:gson:2.13.2
org.jetbrains.kotlin:kotlin-reflect:2.2.21
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
```
=> **kotlinx-coroutines-android:1.9.0 arrives TRANSITIVELY.** We do not need to add it manually.
   Note kotlin-reflect 2.2.21 vs the project's Kotlin 2.2.10 — a minor-version skew to watch
   (kotlin-reflect newer than the compiler can warn; pin/align if the build complains).
   The app already uses Gson (Retrofit converter) — check for a version conflict with 2.13.2.

## MediaPipe

Published versions (https://dl.google.com/dl/android/maven2/com/google/mediapipe/group-index.xml):
```
tasks-text:  ... 0.10.32, 0.10.33, 0.10.35, 0.20230731, 1.0.0
tasks-core:  ... 0.10.35, 0.20230731, 1.0.0
tasks-genai: ... 0.10.33, 0.10.35        (NOTE: no 1.0.0 — genai stops at 0.10.35)
```
=> `com.google.mediapipe:tasks-text:1.0.0` is the latest stable for the embedder path.
=> `tasks-genai` (the older MediaPipe LLM path) is NOT needed — LiteRT-LM supersedes it.

## Build environment (this sandbox)

- Host JDK was 25 (too new for AGP 9.1 comfort). **Installed openjdk-21** at
  `/usr/lib/jvm/java-21-openjdk-amd64` — matches the project's CI (`.github/workflows/ci.yml:23` uses JDK 21).
- **Android SDK installed at `/opt/android-sdk`**: `platform-tools`, `platforms;android-36`,
  `build-tools;36.0.0`. All licences accepted (user explicitly authorised this).
- `local.properties` written with `sdk.dir=/opt/android-sdk`.
- Persisted to `/etc/sandbox-persistent.sh`: `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`.
- Network reachable: dl.google.com, services.gradle.org, maven.google.com, huggingface.co (all 200).

Build command that must work end to end:
```
cd /home/yohann/dev/padam/news-android-ai
./gradlew :News-Android-App:assembleDevDebug
# -> News-Android-App/build/outputs/apk/dev/debug/News-Android-App-dev-debug.apk
```

## Consequences for the plan

1. minSdk 21 -> 24 is cheap. Do it globally in `gradle.properties`; a separate flavor is NOT
   justified for a 3-API-level bump.
2. The REAL device-capability gate is **ABI, not RAM**: no arm64-v8a => feature unavailable, full stop.
   RAM gating is a second, softer tier on top of that.
3. Do not add kotlinx-coroutines explicitly; it comes with the AAR. But DO verify the app module can
   compile Kotlin coroutines given `android.builtInKotlin=true` and no `kotlin-android` plugin.
4. APK size: default (both ABIs) adds ~46 MB uncompressed. Use `abiFilters` or ABI splits.

## BASELINE BUILD — VERIFIED GREEN (2026-08-01)

Before any change:
```
cd /home/yohann/dev/padam/news-android-ai
./gradlew :News-Android-App:assembleDevDebug   # JAVA_HOME=jdk21, ANDROID_HOME=/opt/android-sdk
=> BUILD SUCCESSFUL in 7m 34s (41 tasks, cold)
=> News-Android-App/build/outputs/apk/dev/debug/News-Android-App-dev-debug.apk = 13,918,569 bytes (13.9 MB)
```
So the **baseline APK is 13.9 MB**. Adding litertlm-android (19.8 MB AAR / 46 MB native for both ABIs)
plus mediapipe tasks-text will multiply that. With `abiFilters 'arm64-v8a'` only, expect ~13.9 + ~21 MB
of native + jars = roughly 36-40 MB. Quote that number in the plan, not a guess.

Notes from the baseline run:
- Gradle 9.4.1 wrapper downloads fine; daemon works under JDK 21.
- Build emits `warn: removing resource ... without required default value` for several strings —
  PRE-EXISTING, not caused by us. Do not "fix" it and do not treat it as a regression signal.
- "Deprecated Gradle features were used ... incompatible with Gradle 10" — pre-existing.
- `--no-daemon` cold build is ~7.5 min; warm incremental will be far less.
