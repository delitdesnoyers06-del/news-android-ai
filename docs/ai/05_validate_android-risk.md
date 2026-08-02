# LiteRT-LM Android — verification report (adversarial pass)

Method: I downloaded the real AAR, decompiled `classes.jar` with `javap`, read the native `.so` strings, and **ran real Gradle builds** against a scratchpad copy of `news-android-ai` (no files in either repo were modified). Probe tree: `/tmp/claude-1000/-home-yohann-dev-padam/6f87a778-8192-4b03-9f72-7956a00ea38c/scratchpad/probe/`; logs `build1..7.log`; AAR unpacked at `.../scratchpad/aar/`.

---

## 1. Artifact + resolvable version — **CONFIRMED (brief's `latest.release` works, but PIN IT)**

**EVIDENCE** — `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml`:
```
<latest>0.15.0</latest> <release>0.15.0</release>
versions: 0.0.0-alpha06, 0.8.0, 0.9.0-alpha01..06, 0.9.0-beta, 0.9.0,
          0.10.0, 0.10.2, 0.11.0-rc1, 0.11.0, 0.12.0, 0.13.0, 0.13.1, 0.14.0, 0.15.0
lastUpdated: 20260801003602
```
Group index also lists `litertlm-jvm` (same versions) and a dead `litertlm` (alpha01–05 only).

I empirically resolved `latest.release` in this exact build (`build6.log` → `dependencies` report):
```
+--- com.google.ai.edge.litertlm:litertlm-android:latest.release -> 0.15.0
```
So `latest.release` **is** usable in Gradle 9.4.1. But Maven modules have no status metadata, so Gradle treats every non-SNAPSHOT as `release` — `0.11.0-rc1` and `0.9.0-alpha01` are "releases" to Gradle. The day `0.16.0-alpha01` is published, `latest.release` silently picks it (Gradle version ordering ranks `0.16.0-alpha01 > 0.15.0`). Gradle docs: *"Using dynamic versions and changing modules can lead to unreproducible builds."*

**IMPACT** — Pin `implementation "com.google.ai.edge.litertlm:litertlm-android:0.15.0"`. This library ships breaking API changes between minors (deepwiki's index of the repo does not know `ConversationConfig.enableResponseFormat`, which exists in 0.15.0 — the API moved that fast). Google's own Gallery app pins `litertlm = "0.11.0"`.

**POM (0.15.0) transitive deps — exactly three:** `com.google.code.gson:gson:2.13.2`, `org.jetbrains.kotlin:kotlin-reflect:2.2.21`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`. Identical set in 0.11.0–0.14.0.

---

## 2. minSdk — **REFUTED "undocumented". It is 24.** Build fails at manifest merge, proven.

**EVIDENCE** — the AAR's `AndroidManifest.xml` is plain text (not binary):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.google.ai.edge.litertlm">
    <uses-sdk android:minSdkVersion="24" />
```
And the actual build failure I reproduced (`build3.log`, `:News-Android-App:processOssDebugMainManifest FAILED`):
```
uses-sdk:minSdkVersion 21 cannot be smaller than version 24 declared in library
[com.google.ai.edge.litertlm:litertlm-android:0.15.0] .../jetified-litertlm-android-0.15.0/AndroidManifest.xml
Suggestion: ... or increase this project's minSdk version to at least 24
```
Setting `ANDROID_BUILD_MIN_SDK_VERSION=24` → `BUILD SUCCESSFUL`, APK produced (`build4.log`). The Google docs page `developers.google.com/edge/litert-lm/android` states no minSdk — the docs are silent, the AAR is not.

**A harder gate than minSdk 24 — ABI.** The AAR ships **only two ABIs**:
```
jni/arm64-v8a/liblitertlm_jni.so   21,199,264 B
jni/x86_64/liblitertlm_jni.so      25,222,024 B
```
No `armeabi-v7a`, no `x86`. A 32-bit-only ARM device gets an APK with no matching `.so` and dies in `System.loadLibrary`. Google's Gallery sets `minSdk = 31`.

**IMPACT ON PLAN** — 21 → 24 is mandatory and mechanical (one line in `gradle.properties:20`). But `minSdk 24` alone is a lie: gate the AI feature at runtime on `Build.SUPPORTED_64_BIT_ABIS.length > 0` (plus a RAM check, as Gallery's `ModelPicker` does) and keep the whole feature optional so the reader still works on 32-bit/low-RAM devices. Do **not** raise the app's global minSdk to 31 — that would drop far more users than it buys.

---

## 3. AGP / Kotlin / NDK compatibility — **CONFIRMED compatible; but the brief's "Kotlin 2.2.10" is wrong**

**EVIDENCE** — I compiled the app (AGP 9.1.0, Gradle 9.4.1, `android.builtInKotlin=true`, `android.newDsl=true`, JDK 21) with the dependency added, both a Java and a Kotlin call site: `BUILD SUCCESSFUL` (`build2.log`, `build7.log`). No AGP/Gradle/NDK conflict of any kind. The project has no NDK config and needs none — the AAR ships prebuilt `.so`s.

**Correction to the brief.** `build.gradle:19` declares `id 'org.jetbrains.kotlin.kapt' version '2.3.21' apply false`, which forces the Kotlin Gradle Plugin up:
```
org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.3.21
```
So the effective Kotlin is **2.3.21**, not the `ext.kotlin_version = '2.2.10'` in `build.gradle:5`. Relevant because the AAR carries `kotlin.Metadata(mv=[2,3,0])` (Kotlin 2.3.x) and **Java 21 bytecode (class-file major version 65)**.

**I tried to break it and could not.** I pinned the kapt plugin back to `2.2.10` (verified `kotlin-gradle-plugin:2.2.10` in `buildEnvironment`) and recompiled a Kotlin file using `Engine`, `EngineConfig` with named+default args, `ExperimentalFlags`, `ResponseFormat`, and `Flow<Message>`: **`BUILD SUCCESSFUL`**. So Kotlin 2.2.x reads the 2.3.0 metadata fine — the forward-compat concern is refuted, both configurations work.

**One real constraint stands:** class-file major 65 ⇒ **the build JDK must be ≥ 21**. `compileOptions` says `VERSION_17`, which is source/target only and unaffected; but a CI runner on JDK 17 will fail with `bad class file ... version 65.0`. Verify your CI JDK.

**Also note:** `ExperimentalFlags` members are annotated `@ExperimentalApi`. Kotlin call sites need `@OptIn(ExperimentalApi::class)` — I hit this exact error and it is the *only* thing that failed to compile:
```
e: KotlinProbe.kt:11:9 This API is experimental and temporary. It may change or be removed without notice.
```
Java call sites are unaffected (Java ignores Kotlin opt-in).

**Coroutines — the brief overstates the cost.** `kotlinx-coroutines-android` is **already in the app**, pulled transitively by `androidx.lifecycle:lifecycle-common:2.6.2` at `1.6.4 -> 1.8.1`. Adding litertlm just bumps it: `1.6.4 -> 1.9.0`. Genuinely new transitives: **`kotlin-reflect:2.2.21`** only (gson already present via Retrofit's `converter-gson`).

---

## 4. API surface at 0.15.0 — **Java-callable. CONFIRMED by compiling real Java.** The brief's signatures are stale.

Exact signatures from `javap` on `classes.jar`:

```java
final class Engine implements AutoCloseable {                    // internal `private final Object lock`
    Engine(EngineConfig);  void initialize();  boolean isInitialized();
    Conversation createConversation(ConversationConfig);          // @JvmOverloads
    Session createSession(SessionConfig);  void close();
    static class Companion { void setNativeMinLogSeverity(LogSeverity); }
}
final class EngineConfig {   // NO telescoping overloads — Java must pass all 7
    EngineConfig(String modelPath, Backend backend, Backend visionBackend, Backend audioBackend,
                 Integer maxNumTokens, Integer maxNumImages, String cacheDir);
}
final class ConversationConfig {   // @JvmOverloads: 12-arg down to no-arg
    ConversationConfig(Contents systemInstruction, List<Message> initialMessages,
                       List<? extends ToolProvider> tools, SamplerConfig samplerConfig,
                       boolean automaticToolCalling, List<Channel> channels,
                       Map<String,?> extraContext, LoraConfig loraConfig,
                       boolean prefillPrefaceOnInit, Integer maxOutputToken,
                       ThinkingConfig thinkingConfig, boolean enableResponseFormat);
}
final class SamplerConfig { SamplerConfig(int topK, double topP, double temperature, int seed); }  // seed is NEW vs the brief
abstract class Backend { Backend.CPU(Integer threadCount, Integer numOfThreads); Backend.CPU();
                         Backend.GPU();  Backend.NPU(String nativeLibraryDir);  Backend.GOOGLE_TENSOR }
final class Conversation implements AutoCloseable {
    Message sendMessage(String|Contents|Message, Map<String,?> extraContext, RepetitionPenaltyConfig,
                        NoRepeatNgramConfig, SuppressTokensConfig, Integer maxOutputToken,
                        ThinkingConfig, ResponseFormat);                       // @JvmOverloads, blocking
    void sendMessageAsync(..., MessageCallback, ...);                          // @JvmOverloads, callback
    Flow<Message> sendMessageAsync(..., ResponseFormat);                       // Kotlin-only ergonomics
    void cancelProcess();  int getTokenCount();  BenchmarkInfo getBenchmarkInfo();
    String renderMessageIntoString(Message, Map); String renderPrefaceIntoString();
    boolean isAlive();  void close();
}
interface MessageCallback { void onMessage(Message); void onDone(); void onError(Throwable); }
final class ResponseFormat { static ResponseFormat json(String); static ResponseFormat json(Map<String,?>);
                             static ResponseFormat regex(String);  enum Type { REGEX, JSON_OBJECT } }
final class Session implements AutoCloseable {
    void runPrefill(List<? extends InputData>); String runDecode();
    String generateContent(List<? extends InputData>);
    void generateContentStream(List<? extends InputData>, ResponseCallback);
    void cancelProcess(); boolean isAlive(); void close();
}
final class ThinkingConfig { ThinkingConfig(boolean enableThinking, int thinkingTokenBudget); }
final class Capabilities implements AutoCloseable { Capabilities(String); boolean hasSpeculativeDecodingSupport(); }
final class LiteRtLmJniException extends RuntimeException
```

**Deltas vs the brief (all refutations):** `EngineConfig` has 7 params, not 4 (`visionBackend`, `audioBackend`, `maxNumImages` added). `ConversationConfig` has 12, not 2. `SamplerConfig` has a 4th `seed`. `ExperimentalFlags` has 7 members, not 2 (`enableBenchmark`, `enableSpeculativeDecoding`, `enableConversationConstrainedDecoding`, `convertCamelToSnakeCaseInToolDescription`, `filterChannelContentFromKvCache`, `overwritePromptTemplate`, `visualTokenBudget`).

**Java-callability — CONFIRMED, I compiled this and it built:**
`/tmp/.../scratchpad/probe/News-Android-App/src/main/java/de/luhmer/owncloudnewsreader/aiprobe/JavaProbe.java` calls `new EngineConfig(...)`, `engine.initialize()`, `new ConversationConfig(...12 args...)`, `Contents.Companion.of("...")`, `Message.Companion.user("...")`, `ResponseFormat.json(schema)`, `conv.sendMessage(msg, map, null,null,null,null,null, rf)`, `conv.cancelProcess()`, `close()`. Compiled clean under `compileOssDebugJavaWithJavac`.

Java ergonomics notes: static factories on `Companion` are **not** `@JvmStatic` for `Contents`/`Message` (use `Contents.Companion.of(...)`, `Message.Companion.user(...)`), but `ResponseFormat.json/regex` **are** exposed as real statics. `ExperimentalFlags` is a Kotlin `object` → `ExperimentalFlags.INSTANCE.setEnableConversationConstrainedDecoding(true)` from Java.

**Does Flow force coroutines? No.** Java should use the blocking `sendMessage(...)` on a worker thread, or `sendMessageAsync(String, MessageCallback)` — `MessageCallback` is a plain 3-method interface. The `Flow` overload is one of three shapes and is avoidable entirely.

**RECOMMENDATION:** keep the whole port in Java. Write one thin Java facade (`AiEngineHolder`) using blocking `sendMessage` on the existing `ThreadPoolExecutor`/`JobIntentService` pattern (`services/DownloadWebPageService.java`); no Kotlin, no coroutines, no `@OptIn` needed anywhere. This aligns with the 146-Java/31-Kotlin reality of the codebase.

---

## 5. Constrained/JSON decoding for plain generation — **CONFIRMED, and the brief's mechanism is WRONG**

The brief says `ExperimentalFlags.enableConversationConstrainedDecoding` is the switch and is "primarily wired for function calling". Both halves are misleading for 0.15.0.

**EVIDENCE A — the real gate is a `ConversationConfig` field.** Constant-pool string in `Conversation.class`:
```
String response_format cannot be used unless enableResponseFormat=True was passed to ConversationConfig.
```
thrown as `IllegalArgumentException` at the top of every `sendMessage`/`sendMessageAsync` when `responseFormat != null && !this.enableResponseFormat`. `Engine.createConversation` bytecode passes `ConversationConfig.getEnableResponseFormat()` both into `nativeCreateConversation` (last arg) and into the `Conversation` constructor.

**EVIDENCE B — `ExperimentalFlags.enableConversationConstrainedDecoding` is a *separate*, process-global, read-at-createConversation-time flag.** From `Engine.createConversation` bytecode, offsets 313–328:
```
313: getstatic  ExperimentalFlags.INSTANCE
316: invokevirtual ExperimentalFlags.getEnableConversationConstrainedDecoding:()Z
319/322: ...getFilterChannelContentFromKvCache:()Ljava/lang/Boolean;
325/328: ...getOverwritePromptTemplate:()Ljava/lang/String;
391: invokevirtual LiteRtLmJni.nativeCreateConversation:(J...ZLjava/lang/Boolean;...ZILThinkingConfig;Z)J
```
It is arg 7 of `nativeCreateConversation`; `enableResponseFormat` is arg 15. Two different knobs. Both must be set **before** `createConversation` — neither can be changed per-message.

**EVIDENCE C — plain-text constrained decoding is real.** `resolveResponseFormat` (private, decompiled) returns your `ResponseFormat` unchanged **unless** `automaticToolCalling && toolManager.getToolsDescription().size() > 0 && role != "tool"`. With **no tools registered — our case — the schema is always applied.** Deepwiki on the C++ side confirms the constraint modes are `kTextOnly`, `kFunctionCallsOnly`, `kTextAndOrFunctionCalls`.

**EVIDENCE D — grammar engine and its limits**, from `strings jni/arm64-v8a/liblitertlm_jni.so`:
- Implementation is **LLGuidance** (`third_party/rust/llguidance/v1/`, `llg_constraint.cc`, `llg_constraint_provider.cc`), types `regex` / `json_object` / `llguidance`.
- Supports JSON Schema drafts 04/06/07/2019-09/**2020-12**; keywords incl. `properties`, `items`, `prefixItems`, `anyOf`, `allOf`, `const`, `enum`-adjacent `const`, `minimum`/`maximum`, `minLength`/`maxLength`, `pattern`, `uniqueItems`, `required`.
- **`oneOf` is not supported by default**: `"oneOf constraints are not supported. Enable 'coerce_one_of' option to approximate oneOf with anyOf"`.
- **HARD BLOCKER RISK:** `"Constrained decoding is only supported for SentencePiece tokenizer."` and `"Constrained decoding is not supported for Lfm2DataProcessor."`

**IMPACT ON PLAN — this is very good news for the veille port.** The single highest-risk part of running the scoring prompt on Gemma — *"return exactly one object per article, same order, same id, score in 0..3, flags from a closed vocabulary"* — can be enforced by the decoder rather than hoped for. Use:
```java
new ConversationConfig(sys, emptyList(), emptyList(), sampler, false, emptyList(),
                       emptyMap(), null, false, maxOut, null, /*enableResponseFormat=*/true);
...
ResponseFormat.json(scoreBatchSchema)   // array of {id:int, score:int, themes:[str], flags:[str], why:str}
```
Express the score as `{"type":"integer","minimum":0,"maximum":3}` and flags as `{"enum":[...]}`; avoid `oneOf`. **Do not delete the code-side defensive layer** (brief invariants 2–6): the schema guarantees *shape*, not that ids match ours or that the array length equals the batch length. Keep id-first/position-second alignment and the `score=None` degradation.

**UNKNOWN, and you must test it:** whether the chosen `.litertlm` model uses a SentencePiece tokenizer. Gemma 3 does; the `gemma-4-*-litert-lm` bundles I could not verify. **Resolving test:** on-device, create a conversation with `enableResponseFormat=true` against the candidate model and send one trivial schema; a `LiteRtLmJniException` carrying *"Constrained decoding is only supported for SentencePiece tokenizer"* means that model cannot do structured output and the model choice must change (or the JSON-repair path becomes load-bearing). Do this **before** committing to a default scoring model.

---

## 6. Thread safety + memory — **one Engine, one worker thread. Inference is serialised no matter what you do.**

**Engine lifecycle is internally locked — CONFIRMED from bytecode:** `Engine` has `private final Object lock` and `private volatile Long handle`; `initialize()`, `close()`, `createConversation()`, `createSession()` each contain a `monitorenter` on it. `Conversation` uses an `AtomicBoolean _isAlive` and throws `IllegalStateException("Conversation is closed already.")` after `close()`.

**Concurrency is pointless — PLAUSIBLE (deepwiki on the C++ source, not read by me directly):** multiple `Conversation`s may coexist on one `Engine`, but all inference funnels through `ResourceManager::AcquireExecutorWithContextHandler()`, which takes a `MovableMutexLock` on `absl::Mutex executor_mutex_`, returning a `LockedLlmExecutor`. Only one prefill/decode runs at a time regardless of the `ThreadedExecutionManager`/`SerialExecutionManager` choice.

**Memory — PLAUSIBLE (deepwiki, consistent with the API):** `.litertlm` is **mmap-ed lazily**, section by section, via `LitertLmLoader::MapSection` / `CreateMemoryMapFromScopedFile`; weights are not copied to the heap. Resident set is therefore a subset of file size driven by the working set, plus the KV cache (which *is* real RAM and scales with `maxNumTokens`). `cacheDir` stores rearranged weights / program caches (`.xnnpack_cache` for CPU, `_mldrift_program_cache.bin` for GPU) to speed up later `initialize()` calls.

**IMPACT ON PLAN:**
- Exactly one `Engine` per model, held by a singleton, created off the main thread (`initialize()` is blocking, docs say up to ~10 s; it is not `suspend`).
- Do not parallelise scoring. Serialise the whole triage batch on a single worker thread. The veille `prefilter_top_k` lever (default 60) is what makes this tractable — it is even more load-bearing on-device than in the cloud.
- Sizing `maxNumTokens` is a real RAM decision, not a formality: veille's scoring batch is 10 articles × summary[:1200], which is a big prompt. Consider dropping the on-device batch to 3–5.
- **`cacheDir` must not live on a path that can fill** — this repeats the known PVC-inode failure mode. Put it under `context.getCacheDir()`, never alongside the model.
- **UNKNOWN:** actual resident RSS for a 2.58 GB `gemma-4-E2B` on a mid-range phone. **Resolving test:** `Debug.getMemoryInfo()` / `adb shell dumpsys meminfo` around a real 60-article scoring run on the target device tier. Gallery gates model selection on a memory check for exactly this reason.

---

## 7. Native size — **~45 MiB APK growth as-is; ~21 MiB with an ABI filter; ~9 MiB over the wire via AAB**

`ls -la` on an AGP **debug** APK is worthless here (the incremental packager leaves ~48 MB of dead space — both my APKs measured 61 MB on disk). Measured by summing zip entry sizes with `zipfile`:

| build | Σ compressed entries | Σ uncompressed |
|---|---|---|
| baseline (no litertlm) | 13,247,164 B (12.6 MiB) | 28,318,186 B |
| + litertlm-android 0.15.0 | 60,853,818 B (58.0 MiB) | 77,770,051 B |
| **delta** | **+47.6 MB (45.4 MiB)** | +49.5 MB |

Both `.so`s land in the APK **stored, not deflated** (`compress_type=0`, `extractNativeLibs=false` is the AGP default):
```
lib/x86_64/liblitertlm_jni.so     25,222,024 / 25,222,024
lib/arm64-v8a/liblitertlm_jni.so  21,199,264 / 21,199,264
```
DEX growth is small: `classes17.dex` 3.96 → 4.58 MB compressed, `classes.dex` +16 KB, i.e. **~0.65 MB** for kotlin-reflect + the coroutines bump.

**IMPACT ON PLAN** — the brief's "no `abiFilters`/`splits`" is now a live problem, not a note.
- **arm64-v8a only** (`ndk { abiFilters 'arm64-v8a' }`) drops the delta to **~22 MB**; you lose only the x86_64 *emulator*, which matters for the Espresso/`connectedDevDebugAndroidTest` suite. Recommend: `abiFilters 'arm64-v8a'` on the `oss` release path, `'arm64-v8a','x86_64'` on `dev`/debug so instrumented tests still run.
- Shipping an **AAB** gives per-ABI splits for free; Play recompresses, and the AAR's own deflate ratio (9,230,994 B for arm64) puts the **download delta at ~9 MB**.
- And this is *before* the model. The `.litertlm` file (270 MB – 3.65 GB) must be downloaded at runtime to `getExternalFilesDir` (Gallery pattern), never bundled in `assets/`.

---

## 8. Cancellation — **CONFIRMED, first-class**

**EVIDENCE (bytecode, not docs):** `Conversation.cancelProcess()` and `Session.cancelProcess()` are public; JNI exports confirm them in the native lib:
```
T Java_com_google_ai_edge_litertlm_LiteRtLmJni_nativeCancelProcess
T Java_com_google_ai_edge_litertlm_LiteRtLmJni_nativeConversationCancelProcess
```
Semantics (deepwiki on the C++ side — **PLAUSIBLE**, I did not read `tasks.cc`): a `cancelled` flag is checked each step of the decode loop, so cancellation lands within roughly one token; the operation returns `kCancelled`, which `Conversation$JniMessageCallbackImpl.onError()` maps to a Kotlin `CancellationException`. **The session is not rolled back** — the user message was already prefilled — and conversation history is cleared after cancellation. Calling it from another thread while `sendMessage` blocks is safe. There is also a `Conversation::CancelGroup()` after which the conversation must **not** be reused; `cancelProcess()` leaves it usable.

**IMPACT ON PLAN** — this cleanly satisfies "stop the batch when the user leaves / the service stops". Pattern: hold the `Conversation` in the triage service, call `cancelProcess()` from `onDestroy()`/`onStopCurrentWork()`, catch the cancellation on the worker thread, and treat the in-flight batch exactly like veille's unparseable case — `llm_score = NULL`, rank on `sim_score` alone. Since cancellation is per-token, a granular loop (one small batch at a time) already bounds the worst case; `cancelProcess()` is the belt-and-braces.

---

## Two blockers nobody has flagged yet

**(a) The AAR ships NO ProGuard/R8 consumer rules, and the app uses R8 full mode.**
AAR contents are exactly: `AndroidManifest.xml`, `classes.jar`, `res/`, `R.txt`, the two `.so`s, `LICENSE`, `THIRD_PARTY_NOTICE.txt`. **No `proguard.txt`.** The native side binds by JNI name mangling (`Java_com_google_ai_edge_litertlm_LiteRtLmJni_native*`) and does `FindClass`/`GetMethodID` on strings I found in the `.so`:
```
com/google/ai/edge/litertlm/BenchmarkInfo
com/google/ai/edge/litertlm/InputData$Text  (also $Image, $Audio)
com/google/ai/edge/litertlm/LiteRtLmJniException
onMessage   onDone   onError   getText   getBytes   <init>
```
`proguard-android-optimize.txt` keeps `native <methods>` and their declaring class, so `LiteRtLmJni` survives — but `BenchmarkInfo`, `InputData$*`, `LiteRtLmJniException`, and the `onMessage`/`onDone`/`onError`/`getText`/`getBytes` members are reachable **only** from native and will be renamed or stripped by R8 full mode with `android.r8.strictFullModeForKeepRules=true`. Debug builds (`minifyEnabled false`) will pass; the release build will crash at runtime.
**Fix:** add to `News-Android-App/proguard-rules.pro`:
```
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
```
This cannot be validated by compiling — it needs a `assembleOssRelease` + an on-device smoke run.

**(b) Build JDK must be ≥ 21.** The AAR's classes are class-file major 65. `compileOptions { sourceCompatibility VERSION_17 }` does not protect you; a JDK-17 CI runner fails outright. Verify the CI image (this sandbox has OpenJDK 21.0.11, which is why my builds passed).

---

## What I could not establish

1. **Whether `gemma-4-E2B/E4B-it-litert-lm` uses a SentencePiece tokenizer** — decides whether constrained JSON decoding is available on the intended default model. *Resolving test in §5.* This is the single most important open question, because it determines whether the veille scoring stage is a solved problem or a JSON-repair slog.
2. **Actual resident RAM / tokens-per-second per model per device tier.** No numbers exist in the AAR, the POM, or the docs page. Only a device run answers it. *Resolving test in §6.*
3. **Whether the GPU backend's `<uses-native-library>` manifest entries are truly required** — the brief asserts it; I only corroborated indirectly (the `.so` references `libOpenCL.so`, `libOpenCL-pixel.so`, `libOpenCL-car.so`, `libGLES_mali.so`, `libLiteRtGpuAccelerator.so`, `libLiteRtVulkanAccelerator.so`). I did not find the requirement stated in a source I read.
4. **C++ locking details in §6 and cancellation timing in §8** are from deepwiki's reading of `google-ai-edge/LiteRT-LM`, not from source I read myself — marked PLAUSIBLE, not CONFIRMED. Note deepwiki's index of that repo is demonstrably stale (it denies `ConversationConfig.enableResponseFormat` exists, which the 0.15.0 bytecode proves it does), so treat its API-shape claims as worthless and its architecture claims as indicative.