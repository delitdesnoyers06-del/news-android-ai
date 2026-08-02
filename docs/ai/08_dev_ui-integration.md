# LiteRT-LM runtime, embeddings & model management — implementation spec

All API signatures below were re-verified by `javap` on the actual `litertlm-android-0.15.0.aar` / `tasks-text-1.0.0.aar` in this session. All model facts were verified against the live HF API and by range-fetching the real weight files. Where I say CONFIRMED I ran the check; where I say SPIKE it needs a device.

---

## 0. New evidence that changes prior decisions (read this first)

Four findings from this session override the product spec and parts of the validation reports.

### 0.1 The gating story is backwards — CONFIRMED

`GET https://huggingface.co/api/models?author=litert-community`:

| Repo | `gated` | Anonymous `resolve/main/<file>` |
|---|---|---|
| `litert-community/gemma-4-E2B-it-litert-lm` | **False** | **302 → CDN, `accept-ranges: bytes`, 206 on Range** |
| `litert-community/gemma-4-E4B-it-litert-lm` | **False** | 302 → CDN |
| `litert-community/Gemma3-1B-IT` | `auto` | **HTTP 401**, `x-error-code: GatedRepo`, `www-authenticate: Bearer` |
| `litert-community/gemma-3-270m-it` | `auto` | 401 GatedRepo |
| `litert-community/embeddinggemma-300m` | `auto` | 401 GatedRepo |
| `litert-community/Qwen3-0.6B`, `Qwen3-1.7B`, `SmolLM2-360M-Instruct`, `Phi-4-mini-instruct` | False | 200 |

Consequences:
1. **The default scoring model needs no licence flow at all.** The product spec's S2 browser-accept gate, the `licence_ack_<repo>` flag, and AC #6 do not apply to `gemma-4-E2B`.
2. **It is a 401, not a 403.** Every Gemma-3 repo is gated. Browser acceptance does **not** make an anonymous request succeed — the response demands `Bearer`. So for gated repos an HF token is **mandatory, not a last resort**. `edt_ai_hf_token` cannot be `isPreferenceVisible="false"` behind a double-403. This resolves product open item **R4** decisively, in the direction the product spec feared.
3. All Gemma-3 small models are therefore off the "works out of the box" path. See §3.

### 0.2 `gemma-4-E2B` uses a SentencePiece tokenizer ⇒ constrained decoding works — CONFIRMED

The native lib gates constrained decoding on tokenizer family (`strings liblitertlm_jni.so`):
```
Constrained decoding is only supported for SentencePiece tokenizer.
```
I range-fetched the first 2 MB of `gemma-4-E2B-it.litertlm` (2,588,147,712 B) and found a SentencePiece `ModelProto` vocab in-place — protobuf `\n<len>\n<len><piece>\x15<float32 score>` records with the U+2581 `▁` word-boundary marker:

```
b'...\n\x0f\n\x08\xe2\x96\x81Press\x15\x00X\xd0\xc5\n\x11\n\n\xe2\x96\x81proceed\x15...'
```
| file | `▁` (U+2581) count | `Ġ` (BPE) count |
|---|---|---|
| `gemma-4-E2B-it.litertlm` first 2 MB | **67,583** | 3 |
| `gemma-4-E4B-it.litertlm` first 4 MB | **133,273** | 3 |
| `qwen3_0_6b_mixed_int4.litertlm` first 4 MB | 1 | 36 |
| `SmolLM2_360M_instruct.litertlm` first 4 MB | 0 | 39 |

**Grammar-constrained output (`ResponseFormat.json` / `ResponseFormat.regex`) is available on the Gemma-4 family and not on Qwen/SmolLM.** This partially overrides `product:prompt-adaptation` §0 ("no JSON anywhere, design assumes constrained decoding does not work") — see §4.3. Keep the line format as the wire format; enforce it with `ResponseFormat.regex`.

### 0.3 Exact sizes and SHA-256 for the catalogue — CONFIRMED (resolves product R1)

HF exposes the LFS oid (= SHA-256) via `/api/models/<repo>/tree/main?recursive=true` and via the `x-linked-etag` header. Verified for E2B: `x-linked-size: 2588147712`, `x-linked-etag: "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"`.

| Model file | bytes | sha256 (prefix) |
|---|---|---|
| `gemma-4-E2B-it-litert-lm/gemma-4-E2B-it.litertlm` | 2,588,147,712 | `181938105e0eefd1…` |
| `gemma-4-E4B-it-litert-lm/gemma-4-E4B-it.litertlm` | 3,659,530,240 | (fetch from tree API) |
| `Qwen3-0.6B/qwen3_0_6b_mixed_int4.litertlm` | 497,664,000 | (fetch) |
| `Qwen3-1.7B/Qwen3_1.7B.litertlm` | 2,056,729,520 | (fetch) |
| `SmolLM2-360M-Instruct/SmolLM2_360M_instruct.litertlm` | 373,719,040 | (fetch) |
| `Gemma3-1B-IT/gemma3-1b-it-int4.litertlm` **(gated)** | 584,417,280 | (needs token) |
| `gemma-3-270m-it/gemma3-270m-it-q8.litertlm` **(gated)** | 304,005,120 | (needs token) |
| MediaPipe CDN `embedding_gemma.task` | 183,816,181 | md5 etag `dabc0e55…` only |

Product's "Gemma3-1B ~0.55 GB / 720 MB" copy in §4/S3 and AC #1 is wrong in both directions; use 584,417,280.

### 0.4 LiteRT-LM can open `.task` bundles too — PLAUSIBLE

`strings liblitertlm_jni.so` contains `Expected uncompressed zip archive.`, `Unable to open zip archive.` alongside the `LITERTLM` container reader. `.task` is an uncompressed zip. Do not rely on it; prefer `.litertlm`. Relevant only as a fallback if a repo ships no `.litertlm`.

---

## 1. The engine abstraction

New package: `News-Android-App/src/main/java/de/luhmer/owncloudnewsreader/ai/engine/`.
**All Java.** No Kotlin, no coroutines, no `Flow`, no `@OptIn`. This keeps `detekt` (Kotlin-only, `maxIssues: 0`) and `spotlessCheck` (target `src/*/java/**/*.kt`) trivially green and matches the 146-Java/31-Kotlin reality.

### 1.1 The four interfaces (in `src/main` — no LiteRT types leak through them)

```java
package de.luhmer.owncloudnewsreader.ai.engine;

/** Every failure the AI half can produce. Checked, so callers are forced to degrade. */
public class AiException extends Exception {
    public enum Kind {
        DISABLED,            // cb_ai_enabled == false
        UNSUPPORTED_DEVICE,  // no arm64-v8a, or tier T0
        NOT_INSTALLED,       // sp_ai_model_* points at nothing on disk
        LOAD_FAILED,         // Engine.initialize() threw or timed out
        OUT_OF_MEMORY,
        TIMEOUT,             // per-call watchdog fired
        CANCELLED,           // cancel() / service shutdown / cb_ai_enabled flipped off
        RUNTIME              // LiteRtLmJniException, anything else
    }
    public final Kind kind;
    public AiException(Kind kind, String msg, Throwable cause) { super(msg, cause); this.kind = kind; }
    public boolean retryableNextSync() { return kind == Kind.TIMEOUT || kind == Kind.CANCELLED
                                             || kind == Kind.OUT_OF_MEMORY; }
}
```

```java
/** How the decoder is constrained. FREE_TEXT is the universal floor. */
public enum AiOutputMode { FREE_TEXT, REGEX, JSON_SCHEMA }

/** Immutable per-stage generation parameters. Built once per stage, reused across batches. */
public final class AiPromptSpec {
    public final String  systemInstruction;   // nullable
    public final int     maxOutputTokens;
    public final int     topK;                // 1 = greedy
    public final double  topP;
    public final double  temperature;
    public final int     seed;
    public final AiOutputMode mode;
    public final String  grammar;             // regex pattern or JSON-schema string; null for FREE_TEXT
    public final long    perCallTimeoutMs;
    // + a Builder; no public ctor
}
```

```java
/** A loaded generative model. Obtained only from AiEngineManager.withLlm(). */
public interface AiLlm {
    AiModelInfo model();
    /** True iff this model's tokenizer supports LLGuidance (SentencePiece). See §0.2. */
    boolean supportsConstrainedDecoding();
    /** Opens one conversation. The caller MUST close it. */
    AiConversation start(AiPromptSpec spec) throws AiException;
}

/** One multi-turn exchange. Not thread-safe except for cancel(). */
public interface AiConversation extends java.io.Closeable {
    /** BLOCKING. Runs on the calling (worker) thread. Returns the model's raw text. */
    String send(String userText) throws AiException;
    /** Safe to call from any thread while send() is blocked. Lands within ~1 token. */
    void cancel();
    @Override void close();
}
```

```java
/** A loaded sentence embedder. Obtained only from AiEngineManager.withEmbedder(). */
public interface AiEmbedder {
    int    dim();              // 768 for EmbeddingGemma
    String modelId();          // persisted in AI_EMBEDDING.MODEL
    String embeddingType();    // "CLUSTERING" — persisted in AI_EMBEDDING.EMB_TYPE
    /**
     * Returns a UNIT-NORMALISED float[dim], or null if the vector had zero L2 norm
     * (veille embed.py::_unit semantics — null is a legal, information-bearing result).
     */
    float[] embed(String title, String body) throws AiException;
}
```

```java
public final class AiModelInfo {
    public final String catalogId;     // "gemma-4-E2B"
    public final String displayName;
    public final long   sizeBytes;
    public final java.io.File file;
    public final boolean sentencePiece;
    public final int    maxNumTokens;  // what we passed to EngineConfig
}
```

### 1.2 The lifecycle owner: `AiEngineManager`

**This is the only class in the app that ever touches `com.google.ai.edge.litertlm.*` or `com.google.mediapipe.*`.**

```java
@Singleton
public final class AiEngineManager implements android.content.ComponentCallbacks2 {

    public interface Work<T, R> { R run(T engine) throws AiException; }

    @Inject AiEngineManager(Application app, SharedPreferences prefs,
                            AiModelRepository models, AiCapability caps);

    /** Loads (or reuses a warm) LLM, runs work, then returns. Engine stays warm IDLE_MS. */
    public <R> R withLlm(AiStage stage, Work<AiLlm, R> work) throws AiException;

    /** Loads the embedder, runs work, then returns. */
    public <R> R withEmbedder(Work<AiEmbedder, R> work) throws AiException;

    /** Cancels any in-flight send() and closes everything. Idempotent. */
    public void shutdownNow(String reason);

    public boolean isBusy();
    @Override public void onTrimMemory(int level);
    @Override public void onLowMemory();
    @Override public void onConfigurationChanged(Configuration c) { /* no-op */ }
}
```

**Hard rules baked into the implementation:**

| Rule | Mechanism |
|---|---|
| At most **one** engine (LLM *or* embedder) resident at any instant, process-wide | one `private final ReentrantLock gate`; `withLlm`/`withEmbedder` both take it. `withEmbedder` first closes any warm LLM and vice-versa. This is the structural enforcement of `validate:embeddings` §7 ("sequencing, not linking") and §5 ("embed all → close → open LLM → score → close"). |
| Inference is never parallel | The native side serialises anyway (`ResourceManager::AcquireExecutorWithContextHandler` mutex). The gate makes it explicit and prevents a second `Engine.initialize()` allocating while the first is resident. |
| `Engine.initialize()` never on the main thread | `withLlm` throws `IllegalStateException` if `Looper.myLooper() == Looper.getMainLooper()`. Fail loud in debug, not a 10 s ANR in the field. |
| Consecutive stages reuse one `initialize()` | `IDLE_KEEPALIVE_MS = 90_000`. After `withLlm` returns, a single `ScheduledExecutorService` task closes the engine unless another `withLlm` for the **same model file** arrives first. Satisfies product AC #17 (triage → digest = one `initialize()`). |
| Load has a watchdog | `initialize()` runs on a dedicated thread; the caller waits `LOAD_TIMEOUT_MS = 180_000` (see Pushback #4). On timeout the thread is abandoned (we cannot interrupt native), the manager marks the model `SUSPECT` in `AI_META`, and throws `LOAD_FAILED`. |
| Per-call timeout | See §4.4. |

**When is the multi-GB engine loaded?** Only inside `withLlm`, which is only ever called from:
1. `AiTriageWorker.doWork()` (WorkManager, foreground `dataSync`) — the scoring pass;
2. `AiDigestWorker.doWork()` — the lazy digest abstract;
3. `AiTasteDraftWorker.doWork()` — the manual "suggest interests" button;
4. `AiModelSmokeTest.run()` — the post-download verification (§2.7).

Never from an Activity, never from `onPerformSync` inline, never from a Fragment.

**When is it unloaded?**

| Trigger | Action |
|---|---|
| 90 s idle after the last `withLlm` | scheduled `Engine.close()` |
| `Worker.onStopped()` | `shutdownNow("worker stopped")` → `Conversation.cancelProcess()` then `close()` |
| `cb_ai_enabled` flipped off (`OnSharedPreferenceChangeListener`) | `shutdownNow("disabled")` — satisfies product AC #18 (abort within 5 s; `cancelProcess` lands within ~1 token) |
| `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` or `TRIM_MEMORY_COMPLETE` | `shutdownNow("trim")`; the in-flight batch degrades to `LLM_SCORE = NULL` |
| `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` | **ignored** — this fires every time the user backgrounds the app, which is exactly when we want to be running |
| `onLowMemory()` | `shutdownNow("lowmem")` |

Registration: `app.registerComponentCallbacks(this)` in the manager's constructor. **No edit to `NewsReaderApplication` is needed** — it does not override `onTrimMemory` today and does not need to.

**Dagger.** Never `@Provides Engine`. `AppComponent` gets:
```java
@Component(modules = { ApiModule.class, AiModule.class })
...
void injectService(de.luhmer.owncloudnewsreader.ai.AiWorkerDeps deps);
void injectActivity(de.luhmer.owncloudnewsreader.ai.ui.AiSettingsActivity activity);
void injectActivity(de.luhmer.owncloudnewsreader.ai.ui.AiModelManagerActivity activity);
void injectFragment(de.luhmer.owncloudnewsreader.ai.ui.AiSettingsFragment fragment);
```
`AiWorkerDeps` is a plain injectable holder (`@Inject AiEngineManager`, `@Inject AiModelRepository`, `@Inject SharedPreferences`, `@Inject DatabaseConnectionOrm`) that the `Worker` constructs and injects itself in `doWork()` — WorkManager instantiates the `Worker`, so there is nothing to field-inject into directly, and a `WorkerFactory` is not worth the wiring.

### 1.3 The LiteRT-LM implementation (real signatures)

`src/mlGemma/java/.../ai/engine/impl/LiteRtLlm.java`:

```java
final class LiteRtLlm implements AiLlm, java.io.Closeable {
    private final com.google.ai.edge.litertlm.Engine engine;
    private final AiModelInfo info;

    static LiteRtLlm open(AiModelInfo info, File cacheDir, boolean gpu) throws AiException {
        Backend b = gpu ? new Backend.GPU() : new Backend.CPU();     // Backend.CPU() no-arg ctor exists
        EngineConfig cfg = new EngineConfig(
                info.file.getAbsolutePath(),   // modelPath
                b,                             // backend
                b,                             // visionBackend   (7-arg ctor, no telescoping in Java)
                b,                             // audioBackend
                Integer.valueOf(info.maxNumTokens),
                Integer.valueOf(0),            // maxNumImages
                cacheDir.getAbsolutePath());   // cacheDir
        Engine e = new Engine(cfg);
        e.initialize();                        // BLOCKING, may take 10-180 s
        return new LiteRtLlm(e, info);
    }

    @Override public boolean supportsConstrainedDecoding() { return info.sentencePiece; }

    @Override public AiConversation start(AiPromptSpec spec) throws AiException {
        SamplerConfig sampler = new SamplerConfig(spec.topK, spec.topP, spec.temperature, spec.seed);
        Contents sys = spec.systemInstruction == null
                ? null : Contents.Companion.of(spec.systemInstruction);   // NOT @JvmStatic
        boolean constrained = spec.mode != AiOutputMode.FREE_TEXT && info.sentencePiece;
        ConversationConfig cc = new ConversationConfig(
                sys,
                java.util.Collections.<Message>emptyList(),   // initialMessages
                java.util.Collections.emptyList(),            // tools  -> resolveResponseFormat always applies schema
                sampler,
                false,                                        // automaticToolCalling
                java.util.Collections.emptyList(),            // channels
                java.util.Collections.emptyMap(),             // extraContext
                null,                                         // loraConfig
                false,                                        // prefillPrefaceOnInit
                Integer.valueOf(spec.maxOutputTokens),
                null,                                         // thinkingConfig
                constrained);                                 // enableResponseFormat  <-- MUST be true here
        return new LiteRtConversation(engine.createConversation(cc), spec, constrained);
    }
}
```

```java
final class LiteRtConversation implements AiConversation {
    private final Conversation conv;
    private final ResponseFormat rf;   // null unless constrained

    @Override public String send(String userText) throws AiException {
        Message msg = Message.Companion.user(userText);       // Companion, not static
        Message out = conv.sendMessage(msg,
                java.util.Collections.emptyMap(),  // extraContext
                null,                              // RepetitionPenaltyConfig
                null,                              // NoRepeatNgramConfig
                null,                              // SuppressTokensConfig
                null,                              // maxOutputToken (taken from ConversationConfig)
                null,                              // ThinkingConfig
                rf);                               // ResponseFormat or null
        StringBuilder sb = new StringBuilder();
        for (Content c : out.getContents().getContents()) {
            if (c instanceof Content.Text) sb.append(((Content.Text) c).getText());
        }
        return sb.toString();
    }
    @Override public void cancel() { conv.cancelProcess(); }   // thread-safe, ~1 token latency
    @Override public void close()  { conv.close(); }
}
```

Notes verified this session:
- `EngineConfig` has **7** constructor params in Java (no `@JvmOverloads` telescoping) — the brief's 4-param signature is stale.
- `ConversationConfig` **does** have `@JvmOverloads` (12-arg down to no-arg), but the 12-arg form is the only one that reaches `enableResponseFormat`, so use it.
- `SamplerConfig(int topK, double topP, double temperature, int seed)` — 4 params, `seed` is new.
- `ResponseFormat.json(String)` / `.json(Map)` / `.regex(String)` are **real Java statics**. `Message.Companion.*` and `Contents.Companion.*` are **not** — go through `.Companion`.
- `sendMessage` throws `IllegalArgumentException("... response_format cannot be used unless enableResponseFormat=True ...")` if you pass a `ResponseFormat` without the flag. Guard in code.
- `Conversation` and `Engine` are `java.lang.AutoCloseable` — Java try-with-resources works.

### 1.4 The MediaPipe implementation

`src/mlGemma/java/.../ai/engine/impl/MediaPipeEmbedder.java`:

```java
final class MediaPipeEmbedder implements AiEmbedder, java.io.Closeable {

    /** Built ONCE, reused for every article AND every centroid member. Never use embed(String). */
    private static final TextEmbedder.TextFormatContext CTX =
        TextEmbedder.TextFormatContext.builder()
            .setTaskType(TextEmbedder.EmbeddingType.CLUSTERING)
            .setRole(TextEmbedder.TextRole.QUERY)
            .build();

    private final TextEmbedder embedder;

    static MediaPipeEmbedder open(Context ctx, File taskFile) throws AiException {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(taskFile.getAbsolutePath())   // absolute path, NOT an asset name
                .setDelegate(Delegate.CPU)
                .build();
        TextEmbedder.TextEmbedderOptions opts = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(base)
                .setL2Normalize(true)     // article vectors come back unit-norm
                .setQuantize(false)       // we need float[] to average into a centroid
                .build();
        return new MediaPipeEmbedder(TextEmbedder.createFromOptions(ctx, opts));
    }

    @Override public float[] embed(String title, String body) throws AiException {
        String text = AiText.clip(title + "\n" + body, 1400);   // see below
        List<Embedding> es = embedder.embed(text, CTX).embeddingResult().embeddings();
        if (es.isEmpty()) return null;
        return AiVec.unit(es.get(0).floatEmbedding());          // port of embed.py::_unit
    }
    @Override public int dim() { return 768; }
    @Override public String modelId() { return "embedding_gemma_int4int8"; }
    @Override public String embeddingType() { return "CLUSTERING"; }
}
```

Non-negotiables, all verified:
- **`embed(String)` (1-arg) is banned.** It applies no task prefix; those vectors live elsewhere in the space and cosine against a prefixed centroid is meaningless with no error surface. Enforce with a lint/`detekt` note and a code-review rule; the interface simply does not expose it.
- Text clipped to **1400 chars**, not veille's 2000. The shipped `embedding_gemma.task` graph has a **static `[1,512]` INT32 input** (I confirmed the tensor shape from the flatbuffer in the validation pass); ~2000 chars overflows 512 tokens and gets silently truncated by the tokenizer at an undefined boundary. 1400 chars ≈ 350–400 tokens, comfortably inside.
- `TextEmbedder.cosineSimilarity(Embedding, Embedding)` exists and is `public static` — **do not use it.** It throws on zero-norm and requires wrapping our centroid `float[]` in a synthetic `Embedding`. Write the 6-line `AiVec.dot(unitA, unitB)` instead and keep veille's null/0.0 sentinel semantics (`SIM_SCORE NULL` = never embedded, `0.0` = embedded but no decisions).
- Store the vector as **float32 little-endian BLOB**, byte-identical to veille's `np.dtype("<f4").tobytes()`:
  `ByteBuffer.allocate(dim*4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(v)`. This buys an offline parity test against the Python implementation — dump one vector from each and diff. Cheapest correctness check on the whole embedding half.
- Persist `MODEL` **and** `EMB_TYPE` per row. Changing either invalidates every vector and every centroid; make it an explicit migration, not silent drift.

### 1.5 Fakes for tests

`src/test/java/.../ai/FakeLlm.java` implements `AiLlm`/`AiConversation` returning canned strings from `src/test/resources/ai/*.txt`; `FakeEmbedder` returns deterministic vectors from a seeded RNG. Because the interfaces live in `src/main` and carry no LiteRT types, **every parser, the repair ladder, the centroid maths, the prefilter and the rank formula are pure-JVM testable with no device and no Robolectric.** That is the highest-value structural property of this design.

---

## 2. Model manager

### 2.1 Catalogue data model — hardcoded in `res/raw/ai_model_catalog.json`

Agree with product §5 (hardcode, no remote fetch, F-Droid-audience reasoning is correct). Gson is already a dependency and already `@Provides @Singleton`.

```java
public final class AiCatalogEntry {
    public String  id;              // "gemma-4-E2B" — stable, this is what sp_ai_model_* stores
    public String  displayName;     // "Gemma 4 E2B"
    public String  purpose;         // "llm" | "embedder"
    public String  source;          // "hf" | "url"
    public String  repo;            // "litert-community/gemma-4-E2B-it-litert-lm"  (source=hf)
    public String  revision;        // "main" or a pinned commit sha  -> becomes the on-disk <version>
    public String  fileName;        // "gemma-4-E2B-it.litertlm"
    public String  url;             // full URL, source=url only (the MediaPipe CDN)
    public long    sizeBytes;       // 2588147712
    public String  sha256;          // "181938105e0eefd1..."  null when unavailable (CDN embedder)
    public String  md5;             // "dabc0e55b47b898a38472d5d99f37892" for the CDN embedder
    public boolean gated;           // true => Authorization: Bearer <token> is REQUIRED (401 otherwise)
    public boolean sentencePiece;   // drives supportsConstrainedDecoding()
    public long    minTotalRamBytes;// SELECT_OK threshold — a DATA field, not a formula (see 2.8)
    public int     defaultMaxNumTokens;
    public String  licenseUrl;
}
```

`minTotalRamBytes` is deliberately **data, not a formula**. The formula (`size × 1.4 + 1 GB`) is a guess; making it a catalogue field lets the device spike replace it with a measured number without touching code. Seeded values:

| id | sizeBytes | minTotalRamBytes | maxNumTokens |
|---|---|---|---|
| `embedding-gemma-300m` | 183,816,181 | 2 GB | — |
| `qwen3-0.6b-int4` | 497,664,000 | 2 GB | 2048 |
| `smollm2-360m` | 373,719,040 | 2 GB | 2048 |
| `gemma3-1b-it-int4` (gated) | 584,417,280 | 2 GB | 2048 |
| `qwen3-1.7b` | 2,056,729,520 | 4 GB | 2048 |
| `gemma-4-E2B` | 2,588,147,712 | **4.5 GB** | 2048 |
| `gemma-4-E4B` | 3,659,530,240 | **6.5 GB** | 2048 |

### 2.2 Storage layout

```
getExternalFilesDir(null)/ai-models/<id>/<revision>/<fileName>          final weights
                                                   /<fileName>.part     in-flight body
                                                   /<fileName>.meta     JSON: {url,size,received,sha256,startedAt}
getCacheDir()/litertlm/<id>/                                            EngineConfig.cacheDir
```

Two deliberate choices:
- Weights on **external files dir** (Gallery convention; not OS-reclaimable; survives `pm clear` on most devices). Different root from `NewsFileUtils.getCacheDirPath()` which returns `getExternalCacheDir()` (`NewsFileUtils.java:119-121`) — so the existing `edt_clearCache` handler cannot delete 2.6 GB. Verified: `NewsFileUtils` only ever touches `getExternalCacheDir()/web-archive/` and the podcast dir.
- `EngineConfig.cacheDir` on **internal** `getCacheDir()`, a *different volume* from the weights. This is the PVC-inode lesson: never let the compiled-cache growth exhaust the volume holding the thing it caches. It is OS-reclaimable; eviction costs a slow next `initialize()`, not a broken model.

### 2.3 Download: OkHttp 5, already a dependency

No WorkManager for the download, no `DownloadManager`, no new library. `okhttp:5.3.2` is already in `build.gradle:185`.

```java
public final class AiModelDownloadService extends android.app.Service {
    // foregroundServiceType="dataSync", ongoing low-priority notification w/ determinate progress
    // + a Cancel action, mirroring DownloadWebPageService's notification shape (NOT its class).
}
```

Deliberately a plain foreground `Service`, not `JobIntentService`: a 2.6 GB transfer exceeds JobScheduler's ~10 min window, and this is user-initiated and unbounded. (Note: `DownloadWebPageService` is *also* a plain `Service` — `services/DownloadWebPageService.java:48` — and its `<service>` declaration has **no `foregroundServiceType`**, which means it is already crashing on targetSdk 34+ with `MissingForegroundServiceTypeException`. Do not copy that class; do copy its notification/`ThreadPoolExecutor`/EventBus-stop shape.)

Core loop:

```java
Request.Builder rb = new Request.Builder().url(resolveUrl(entry));
if (received > 0) rb.header("Range", "bytes=" + received + "-");
if (entry.gated)  rb.header("Authorization", "Bearer " + hfToken);
try (Response r = client.newCall(rb.build()).execute()) {
    if (r.code() == 401 || r.code() == 403) throw new GatedRepoException(r.code(), r.header("x-error-code"));
    if (received > 0 && r.code() != 206) { part.delete(); received = 0; /* restart, toast */ }
    try (BufferedSource src = r.body().source();
         BufferedSink  dst = Okio.buffer(Okio.appendingSink(part))) {
        long n; byte[] ignored;
        while ((n = src.read(dst.getBuffer(), 256 * 1024)) != -1) {
            dst.emitCompleteSegments();
            received += n;
            if (received - lastMeta > 4L * 1024 * 1024) { writeMeta(received); lastMeta = received;
                if (freeBytes() < 64L*1024*1024) throw new LowDiskException(); }
            if (cancelled.get()) throw new InterruptedIOException();
            publishProgress(received, total);
        }
    }
}
```

**Resume — one correction to the product spec.** `If-Range: <etag>` is unusable here. The HF `resolve/main/...` URL 302s to a **signed CDN URL with `Expires=` ~15 min**, and the CDN's `etag` is the *xet content hash*, different from the origin's `x-linked-etag` (the SHA-256). Verified this session:

```
resolve/main → 302, x-linked-etag: "181938105e0eefd1…"  (sha256)
CDN 206     → etag: "ee3c29acd58e68be…"                 (xet hash)
```
So: **never persist the CDN URL.** Persist the `resolve/main/...` URL and re-resolve on every resume. Validate the resume with `x-linked-size` (must equal `entry.sizeBytes`) and the final SHA-256, not with `If-Range`. Range itself is fully supported — I got `HTTP/2 206`, `content-range: bytes 1000000-1000100/2588147712`.

Progress is published as a sticky EventBus event (`AiModelDownloadProgressEvent`) — the app already uses EventBus 3.3.1 for exactly this shape (`SyncStartedEvent`/`SyncFinishedEvent`).

Wi-Fi-only: `ConnectivityManager.registerNetworkCallback` with `NET_CAPABILITY_NOT_METERED`; on `onLost` set `cancelled` and mark `PAUSED_WAITING_WIFI`; on `onAvailable` re-enqueue. 3 automatic resumes, 5/15/45 s backoff.

### 2.4 The gated flow — REWRITTEN (product §S2 is wrong)

Two regimes, and they are not variations of each other:

**Regime A — ungated (`gemma-4-E2B`, `gemma-4-E4B`, all Qwen/SmolLM/Phi, and the MediaPipe CDN embedder).**
Plain GET. No licence dialog, no browser, no token. Verified 302 → 200/206 anonymously. This is the default path for **every model a normal user will ever install.**

**Regime B — gated (`Gemma3-1B-IT`, `gemma-3-270m-it`, `embeddinggemma-300m`, all Gemma-3 repos).**
An anonymous GET returns **401 `GatedRepo`** with `www-authenticate: Bearer`. Accepting the licence in a browser does **not** make an anonymous request succeed — the request must carry a token from an account that has accepted. So the flow is:

1. Catalogue rows with `gated: true` render with a `Sign in to Hugging Face` chip, **not** a `Download` chip.
2. Tapping it opens a bottom sheet: *"This model is published under the Gemma licence. You need a free Hugging Face account: accept the terms on the model page, create a read token, and paste it here."* with `[Open model page]` (Custom Tab → `licenseUrl`; `androidx.browser:1.9.0` is already a dependency), `[Open token page]` (→ `https://huggingface.co/settings/tokens`), and a paste field bound to `edt_ai_hf_token`.
3. With a token present, the row shows `Download`. A 401 *with* a token means "you have not accepted the terms for this repo" — surface exactly that, with `[Open model page]`, not "download failed".
4. `edt_ai_hf_token` is **always visible** in AI settings (not `isPreferenceVisible="false"` behind a double-403), because for these repos it is the only path, not a workaround.

Consequence for product AC #6 and #7: rewrite. AC #6 as written ("Tapping Download on gemma-4-E2B with no licence ack opens the browser") is testing a flow that does not exist — E2B is ungated.

### 2.5 Integrity check

1. **SHA-256** of the completed `.part` vs `entry.sha256` (the HF LFS oid — available from the tree API and from `x-linked-etag`). `MessageDigest.getInstance("SHA-256")` streamed at 1 MB/chunk; ~8 s for 2.6 GB on a mid-range phone, run inside the same foreground service. Mismatch → delete, one silent retry, then `BROKEN` + "This model changed on Hugging Face; update the app" (product §5's stated accepted cost, surfaced honestly).
2. For the CDN embedder there is no SHA-256, only an **MD5 `etag`** (`dabc0e55b47b898a38472d5d99f37892`). Check MD5. Pin both in the catalogue.
3. **Smoke load** — see §2.7 and Pushback #4.
4. Only after both pass: `part.renameTo(final)` — an atomic rename on the same filesystem. **This is why the product's `size × 1.15` disk rule is wrong** (§Pushback #5).

### 2.6 Deletion

`AiModelRepository.delete(id)` removes the whole `ai-models/<id>/<revision>/` directory **and** `getCacheDir()/litertlm/<id>/`. Then, for every pref key in `{sp_ai_model_triage, sp_ai_model_digest, sp_ai_model_enrich, sp_ai_model_learn}` whose value equals `id`, reset to its sentinel and post a toast. Never leave a dangling path — a stale `modelPath` produces a silent permanent AI outage, which is the single worst failure shape in this feature.

### 2.7 Post-download smoke load

On a background thread, watchdog **180 s** (not 45 s — see Pushback):
```java
AiModelInfo info = repo.infoFor(id);
try (LiteRtLlm llm = LiteRtLlm.open(info, smokeCacheDir, /*gpu=*/false)) {   // CPU always for smoke
    AiPromptSpec spec = AiPromptSpec.builder().maxOutputTokens(8).topK(1).temperature(0.0).build();
    try (AiConversation c = llm.start(spec)) { String s = c.send("Say OK."); ok = s != null; }
}
// then, if info.sentencePiece: repeat with mode=REGEX, grammar="[0-3]" — this is the ONE test that
// proves constrained decoding actually works on this file. Result cached in AI_META.
```
The second half is the on-device resolution of the SentencePiece question for any *new* model added to the catalogue. If it throws `LiteRtLmJniException` carrying *"Constrained decoding is only supported for SentencePiece tokenizer"*, set `sentencePiece=false` in `AI_META` for that install and fall back to the unconstrained line parser — the feature still works, just with a higher repair rate.

### 2.8 "Is this device capable" — the concrete rule

```java
public final class AiCapability {
    public enum Tier { UNSUPPORTED, LIGHT, FULL }

    private static final long GB = 1024L * 1024L * 1024L;

    public static boolean hasArm64() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;   // x86_64 emulators fall through -> allowed only in dev builds, see below
    }

    public static long totalRam(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;          // API 16+. STABLE across launches. This is the tiering signal.
    }

    public static Tier tier(Context ctx) {
        boolean abiOk = hasArm64() || (BuildConfig.DEBUG && isX86_64());
        if (!abiOk) return Tier.UNSUPPORTED;                 // LiteRT-LM ships NO armeabi-v7a, NO x86
        long ram = totalRam(ctx);
        if (ram <  3 * GB)  return Tier.UNSUPPORTED;
        if (ram <  6 * GB)  return Tier.LIGHT;
        return Tier.FULL;
    }

    /** Per-model gate. Data-driven, not a formula. */
    public static boolean selectOk(Context ctx, AiCatalogEntry e) {
        return totalRam(ctx) >= e.minTotalRamBytes;
    }

    /** Per-model disk gate. Rename, not copy -> no 1.15x factor. */
    public static boolean downloadOk(Context ctx, AiCatalogEntry e) {
        StatFs fs = new StatFs(ctx.getExternalFilesDir(null).getPath());
        return fs.getAvailableBytes() >= e.sizeBytes + 256L * 1024 * 1024;
    }

    /** Runtime defer only. NEVER a tiering signal. */
    public static boolean lowMemoryRightNow(Context ctx) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.lowMemory || mi.availMem < mi.threshold * 2;
    }
}
```

**`getMemoryClass()` / `getLargeMemoryClass()` are deliberately NOT used.** They bound the *Java heap*; LiteRT-LM allocates natively and mmaps the weights, so a 192 MB memory class on a 6 GB phone says nothing about whether E2B loads. Using it would exclude every device. (The task asked for a rule "using `ActivityManager.MemoryInfo` / `getMemoryClass`" — the answer is `MemoryInfo.totalMem`, and `getMemoryClass` is the wrong instrument here.)

**ABI is the hard gate, RAM is the soft one.** No `arm64-v8a` ⇒ `UNSUPPORTED`, full stop — there is no armeabi-v7a `.so` in the AAR, so the alternative is an `UnsatisfiedLinkError` inside a background service that the user experiences as "the AI folder is always empty".

Override: a row failing `selectOk` is greyed with "Needs ~4.5 GB RAM · this device has 3.8 GB", plus an overflow `Try anyway` behind a confirm dialog. OEM `totalMem` reporting is inconsistent enough that a hard lock generates bug reports; the override is free and honest. A row failing `hasArm64()` has **no override** — it is a physical impossibility.

---

## 3. Stage → model defaults, FINAL

Reconciled against the gating and tokenizer evidence in §0. **Two product picks are overridden.**

| Stage | T2 FULL (≥6 GB) | T1 LIGHT (3–6 GB) | T0 | Calls/run | Constrained? | Rationale |
|---|---|---|---|---|---|---|
| **Embedding** | `embedding-gemma-300m` (`.task`, 175 MiB, **MediaPipe CDN, ungated**) | same | — | 1 per *new* article, capped 60/sync | n/a | Mandatory floor. 123 MB RAM @ seq512, 169–200 ms/item flagship / 450–700 ms mid-range. Multilingual (31k CJK + 45k Cyrillic SP pieces vs Gecko's 1.1k/150). **NOT from `litert-community/embeddinggemma-300m`** — that repo is gated *and* ships only bare `.tflite`, no bundle. |
| **Triage / scoring** | **`gemma-4-E2B` (2.588 GB)** | **`qwen3-0.6b-int4` (498 MB)** | — | = `sp_ai_batch_budget` ÷ batch size | E2B **yes** (SentencePiece, confirmed); Qwen **no** (BPE) | E2B is ungated, is the docs default, has 1.14M downloads, and — decisive — supports grammar-constrained output, which turns the single highest-risk part of the port into a decoder guarantee. |
| **Digest abstract** | `__same_as_triage__` | `__same_as_triage__` | — | 1, lazy on open | no (prose) | Reuses the warm engine: 0 extra bytes, 0 extra `initialize()`. |
| **Enrichment** | `__off__` (opt-in → `__same_as_triage__`, or `gemma-4-E4B` at ≥6.5 GB) | **row hidden** | — | 0 default | yes | Agree with product: keep the capability, default off. |
| **Taste / rubric learn** | `__off__` (manual button → `__same_as_triage__`) | **row hidden** | — | 0 automatic | yes | Manual, diff-reviewed, never on a timer. |

### 3.1 Overrides of the product spec, with reasons

**Override 1 — T1 default is `qwen3-0.6b-int4`, not `Gemma3-1B-IT`.**
`litert-community/Gemma3-1B-IT` is `gated: auto` and returns **401 GatedRepo** anonymously. Shipping it as the *default* for the low-RAM tier means every low-end user must create a Hugging Face account, accept a licence, mint a token and paste it into a text field before the feature does anything. That is not a default; it is a wall. `Qwen3-0.6B` (`qwen3_0_6b_mixed_int4.litertlm`, 497,664,000 B, ungated, 40k downloads) is 15 % smaller and installs with one tap.

Cost of the override: Qwen is BPE, so **no constrained decoding on T1**. That is exactly what `product:prompt-adaptation`'s line-format + repair-ladder design already assumes, so it costs nothing architecturally — T1 simply runs the unconstrained path, and the acceptance bar for T1 (<15 % post-repair failure) is the one that matters.

`Gemma3-1B-IT` stays in the catalogue as an **advanced, token-gated** entry, labelled *"Gemma 3 1B — better than Qwen at instructions, but needs a Hugging Face account"*. If the on-device eval shows it beats Qwen materially on the §6 fixture, promote it and accept the token friction; that is a measurement, not an argument.

**Override 2 — the embedder does not come from Hugging Face at all.**
`https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task` — HTTP 200, 183,816,181 B, `last-modified: 2026-06-23`, no auth. Three entries in the zip: `gemma.tflite` (179,132,472), `manifest.pb` (50), `sentencepiece.model` (4,683,319). The model-manager therefore has **two download regimes** (`source: "hf"` vs `source: "url"`); do not build one generic HF downloader and assume it covers both.

**Override 3 — GPU backend defaults to OFF on every tier.**
Product row 14 says `true` on T2. Measured GPU numbers for the *embedder* are 1445 ms init and 762 MB RSS vs 24.9 ms / 123 MB on CPU. For the LLM, GPU-delegate OOM is the single most common LiteRT crash class and we have zero device data. Default `false` everywhere, opt-in with the copy product already wrote ("Faster, but warms the phone. Turn off if triage crashes."). Flip the default only after the §6 device spike. Smoke loads always run on CPU.

**Override 4 — `sp_ai_batch_budget` semantics.**
Product defines it as "articles to score each run" (30 / 15). Keep the name and the values, but note it is **also the embed cap** (`AI_EMBED_MAX_PER_SYNC`), because every candidate must be embedded before the prefilter can rank it. The single lever must cover both stages or the embed stage grows unbounded on a fresh install with 400 backlogged articles.

### 3.2 Fallback chain when the preferred model is absent

Resolved by `AiModelResolver.resolve(stage)`, in order. Every step is silent and logged; the user sees the result in the status strip, never a crash.

```
1. pref value sp_ai_model_<stage>
     == "__off__"            -> stage disabled, caller degrades (invariant #8)
     == "__same_as_triage__" -> restart resolution at sp_ai_model_triage
     == "<id>"               -> if installed AND selectOk AND not BROKEN -> USE IT
2. tier default              -> gemma-4-E2B (T2) / qwen3-0.6b-int4 (T1), if installed
3. any installed LLM passing selectOk, largest first
4. any installed LLM at all (ignore selectOk; the user pressed "Try anyway")
5. none -> AiException(NOT_INSTALLED)
```

Degradation on `NOT_INSTALLED` / `UNSUPPORTED_DEVICE`, per stage — this is veille invariant #8 made concrete:

| Missing | Behaviour |
|---|---|
| embedder | `SIM_SCORE = NULL`, prefilter falls back to recency (`PUB_DATE DESC, undated last`), the For-You list is a plain recency list, strip says so |
| triage LLM | `LLM_SCORE = NULL` (**not 0**), `RANK_SCORE = round(sim, 3)`, rank on similarity alone, no pill, no why-line; **retryable next sync** |
| digest LLM | digest card renders with count + theme chips (both pure SQL), abstract omitted |
| enrich LLM | keep the stage-2 why-lines |
| learn LLM | "Suggest from my choices" button hidden |

**The embedder is the only stage that must never be `__off__`.** It is what makes the feature a *taste* model; without it the whole thing is a recency list with extra steps. 175 MiB, ungated, one tap.

---

## 4. Prompt-execution layer

`src/main/java/de/luhmer/owncloudnewsreader/ai/prompt/`.

### 4.1 Template → call

Agree with `product:prompt-adaptation`: `res/raw/prompt_*.txt`, not `strings.xml` (no `%`/`'`/XML escaping, no translation pipeline).

```java
public final class PromptTemplate {
    public static PromptTemplate load(Context c, @RawRes int id);   // cached in a static Map
    public String render(Map<String,String> vars);                  // {{name}} -> value, single pass
    /** Throws in DEBUG if any {{...}} remains unsubstituted. Silently strips in release. */
}
```
Single-pass substitution (build the output by scanning for `{{`), **not** chained `String.replace` — a chained replace lets a value containing `{{x}}` be re-substituted, which is a prompt-injection vector given `{{articles}}` is scraped text.

### 4.2 The call

```java
public final class LlmCall {
    public static final class Result {
        public final String raw;           // never null; "" on failure
        public final AiException failure;  // null on success
        public final long wallMs;
        public final boolean constrained;  // whether the grammar was actually applied
    }
    /** Runs on the caller's worker thread. NEVER throws. */
    public static Result run(AiLlm llm, AiPromptSpec spec, String userText,
                             CancelToken token, AiLog log);
}
```
`run` wraps everything in try/catch and converts to `Result.failure`. **No exception ever escapes into `AiTriageWorker.doWork()`, and therefore never into `onPerformSync`.** This is veille invariant #2 + #8 as a single code guarantee.

Sampler, per `product:prompt-adaptation` §0, with the lesson from veille invariant #7 (a rejected sampler param must not kill the AI half):
```java
SamplerConfig s;
try { s = new SamplerConfig(1, 1.0, 0.0, 0); }          // greedy for score/enrich/abstract
catch (RuntimeException ignored) { s = new SamplerConfig(40, 0.95, 0.8, 0); }  // library defaults
```
Taste draft only: `new SamplerConfig(40, 0.95, 0.3, 0)`.

### 4.3 Response format — the unification

**Recommendation: one wire format (the pipe-delimited line format), enforced by `ResponseFormat.regex` where the tokenizer allows it.**

This reconciles `product:prompt-adaptation` (no JSON — correct, for the right reasons) with the new SentencePiece evidence (constrained decoding works — so *ask* for the line format, and *enforce* it):

```java
// batch of N articles
String grammar = "(?:[1-9][0-9]?\\|[0-3]\\|[a-z0-9,\\-]{0,80}\\|[^\\n|]{0,160}\\n){" + n + "}";
AiPromptSpec spec = AiPromptSpec.builder()
        .systemInstruction(scoreSystem)
        .mode(AiOutputMode.REGEX).grammar(grammar)     // downgraded to FREE_TEXT if !sentencePiece
        .maxOutputTokens(70 * n)
        .topK(1).topP(1.0).temperature(0.0).seed(0)
        .perCallTimeoutMs(120_000)
        .build();
```
Why regex over JSON schema:
- **One parser, one prompt, one eval fixture** for both tokenizer families. A JSON path would mean two prompts, two parsers and two eval baselines.
- It makes the *structural* guarantee mechanical (exactly N lines, index 1–N, score 0–3, no pipe in `why`) while leaving the *semantic* guarantee (is score 2 right?) to the model, which is the correct split.
- LLGuidance supports `regex` and `json_object` types (both present in the `.so`); JSON Schema draft 2020-12 is supported but **`oneOf` is not** (`"oneOf constraints are not supported. Enable 'coerce_one_of'"`) — a constraint we simply avoid by not using JSON.

**The code-side parser is not deleted.** The grammar guarantees shape, not that the model didn't renumber, duplicate or hallucinate a tag. `ScoreLineParser` (per `product:prompt-adaptation` §5.1) runs unchanged on both paths. On the constrained path its failure rate should be ~0; that delta is the metric that proves constrained decoding is actually on.

SPIKE required: confirm on device that `ResponseFormat.regex` with a `{N}` repetition quantifier is accepted by LLGuidance and does not deadlock the decoder when the model wants to stop early. If it misbehaves, drop the `{N}` and use `(?:...\n)+` with the repair ladder handling short output.

### 4.4 Timeouts and cancellation

```java
final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    void cancel(); boolean isCancelled();
}
```
Per-call watchdog, because `sendMessage` is blocking with no timeout parameter:
```java
ScheduledFuture<?> wd = watchdog.schedule(conv::cancel, spec.perCallTimeoutMs, MILLISECONDS);
try { raw = conv.send(userText); } finally { wd.cancel(false); }
```
`Conversation.cancelProcess()` is safe from another thread while `sendMessage` blocks and lands within roughly one token (JNI export `nativeConversationCancelProcess` confirmed). The conversation stays usable after `cancelProcess()`; conversation history is cleared. On cancellation the in-flight batch → `LLM_SCORE = NULL`, marked retryable.

Cancellation sources, all routed to the same token: `Worker.onStopped()`; `cb_ai_enabled` → false; `onTrimMemory` critical; `AiEngineManager.shutdownNow()`; the notification's Stop action.

Timeout budgets:

| Stage | per-call | whole-run |
|---|---|---|
| Engine load | 180 s | — |
| Score, batch of 4 | 120 s | `sp_ai_batch_budget` × 12 s, hard cap 12 min |
| Score, batch of 1 (repair split) | 45 s | — |
| Digest abstract | 90 s | — |
| Enrich (one article) | 120 s | 10 articles |
| Taste draft | 180 s | — |
| Embed (per article) | 8 s | 60 items or 3 min, whichever first |

### 4.5 Batching

```java
public final class ScoreBatcher {
    /** Default 4 (>=1B models); 1 for sub-500M models; forced 1 on repair failure. */
    static int batchSizeFor(AiModelInfo m) { return m.sizeBytes < 500_000_000L ? 1 : 4; }
}
```
One `AiConversation` **per batch**, closed after the batch (including its repair turn). Not one per run: the repair turn needs the previous turn in context (that is the whole point of `_REPAIR`), but batch N+1 must not carry batch N's tokens in the KV cache — that is unbounded KV growth and a slow, drifting decode.

The repair ladder, per batch (`product:prompt-adaptation` §5.1, unchanged — it is right):
```
parse(raw) -> Map<Integer, ScoreLine>
missing = (1..N) \ keys
if missing.isEmpty()                      -> done
attempt 0: conv.send(prompt_score_repair) -> reparse, merge (existing wins)
still missing && N > 1                    -> re-run ONLY the missing articles as N batch-of-1 calls,
                                             FRESH conversation each
still missing                             -> those articles get LLM_SCORE = NULL
```
Splitting beats re-asking because the failure mode being repaired is almost always length/alignment, which batch=1 eliminates by construction.

**Terminal guarantee, asserted by a test:** every article that enters `AiTriageWorker` exits with a row in `AI_SCORE`. `LLM_SCORE = NULL` on failure, never a missing row. Assert it over a run with `FakeLlm` returning garbage (veille invariant #2). `NULL ≠ 0`: NULL means "never judged" → rank on sim, retry next sync; 0 means "judged, off-topic" → final.

### 4.6 Progress and resumability

`AI_SCORE.SCORED_AT` is written per article as the batch completes, so a killed run resumes at the first article with `SCORED_AT IS NULL` — required because API 34+ imposes a cumulative ~6 h/24 h quota on `dataSync` foreground services, and because the user will background/kill the app mid-run.

---

## 5. Gradle, manifest, ProGuard — exact text

### 5.1 `gradle.properties`

```diff
-ANDROID_BUILD_MIN_SDK_VERSION=21
+ANDROID_BUILD_MIN_SDK_VERSION=24
```
Forced: both AARs declare `<uses-sdk android:minSdkVersion="24"/>` and manifest merge hard-fails otherwise. The usual escape hatch `<uses-sdk tools:overrideLibrary="...">` is unavailable because `gradle.properties:30` sets `android.usesSdkInManifest.disallowed=true` — and it would only defer a guaranteed `UnsatisfiedLinkError` anyway. Nothing in the codebase breaks: there are zero `LOLLIPOP` and zero `JELLY_BEAN` references in `src/main`.

Do **not** touch `android.dependency.useConstraints=true` or `android.r8.strictFullModeForKeepRules=true`; handle their consequences below.

`build.gradle:5` `ext.kotlin_version = '2.2.10'` → `'2.2.21'`, to align with the `kotlin-reflect:2.2.21` that litertlm drags in under `useConstraints=true`. (Note the effective KGP is already 2.3.21, forced by `id 'org.jetbrains.kotlin.kapt' version '2.3.21'` at `build.gradle:19`.)

### 5.2 `News-Android-App/build.gradle`

```groovy
    flavorDimensions = ["default", "ml"]

    productFlavors {
        // 100% Open-Source Edition
        oss { dimension "default" }
        dev { dimension "default"; applicationIdSuffix ".dev" }

        // No on-device AI. This is the F-Droid / oss-release artifact: byte-identical
        // dependency graph to the pre-AI build.
        mlNone  { dimension "ml" }

        // On-device AI. arm64-v8a only (LiteRT-LM ships no armeabi-v7a and no x86);
        // x86_64 is kept so connectedAndroidTest still runs on an emulator.
        mlGemma {
            dimension "ml"
            applicationIdSuffix ".ai"
            ndk { abiFilters 'arm64-v8a', 'x86_64' }
        }
    }

    packagingOptions {
        resources {
            excludes += ['META-INF/LICENSE.txt', 'META-INF/NOTICE.txt', 'META-INF/NOTICE',
                         'META-INF/DEPENDENCIES', 'LICENSE.txt',
                         'META-INF/services/javax.annotation.processing.Processor']
        }
        jniLibs {
            // 6.67 MB (arm64) of TextSummarizer/TextProofreader JNI we never call.
            // TextEmbedder binds to libmediapipe_tasks_jni.so (tasks-core), not this one.
            excludes += ['**/libmediapipe_tasks_textgenai_jni.so']
        }
    }
```

```groovy
dependencies {
    // ... existing ...

    // --- on-device AI (mlGemma flavor only) -------------------------------------
    // PINNED. Do NOT use latest.release: Maven modules carry no status metadata, so
    // Gradle would silently pick 0.16.0-alpha01 the day it is published.
    mlGemmaImplementation 'com.google.ai.edge.litertlm:litertlm-android:0.15.0'

    // Text embedding. tasks-core:1.0.0 arrives transitively.
    // transport-backend-cct is Google Clearcut telemetry — excluded, see PRIVACY.md.
    mlGemmaImplementation('com.google.mediapipe:tasks-text:1.0.0') {
        exclude group: 'com.google.android.datatransport'
    }

    // WorkManager, Java API (NOT -ktx: no coroutine requirement).
    mlGemmaImplementation 'androidx.work:work-runtime:2.10.6'
}
```

Notes, all verified:
- `litertlm-android:0.15.0` POM has exactly three compile deps: `gson:2.13.2` (already present, `build.gradle:151`), `kotlin-reflect:2.2.21` (**new**), `kotlinx-coroutines-android:1.9.0` (bumps the existing transitive `1.6.4 → 1.8.1` up to `1.9.0`). **Do not add coroutines manually.**
- `tasks-core:1.0.0` POM compile deps, read from the file: `flogger:0.6`, `flogger-system-backend:0.6`, `guava:27.0.1-android`, `protobuf-javalite:4.26.1`, `transport-api:3.0.0`, **`transport-backend-cct:3.1.0`**, **`transport-runtime:3.1.0`**. The exclude is a policy requirement (`PRIVACY.md:3`), and MediaPipe's logging path is lazily initialised — **verify at runtime that `TextEmbedder.createFromOptions` still works with the exclude in place; if it `NoClassDefFoundError`s, the exclude must become a stub source set, not a revert.**
- `guava:27.0.1-android` collides with `listenablefuture:1.0`; `proguard-rules.pro:87-103` already carries the guava `-dontwarn` block. Verify resolution under `android.dependency.useConstraints=true` with `./gradlew :News-Android-App:dependencies --configuration ossMlGemmaDebugRuntimeClasspath` **before** any code is written.
- Both MediaPipe AARs carry an identical 2.19 MB `META-INF/NOTICE`; pre-empted in the excludes above.
- No repository change: both artifacts are on `google()`, which is already first and unfiltered (`build.gradle:105`).

**CI (`.github/workflows/ci.yml`)** — `assembleDev` becomes ambiguous and `test` fans out to 8 tasks. Pin them:
```yaml
  - run: ./gradlew :News-Android-App:assembleDevMlGemmaDebug :News-Android-App:assembleOssMlNoneRelease
  - run: ./gradlew :News-Android-App:testOssMlNoneDebugUnitTest :News-Android-App:testOssMlGemmaDebugUnitTest
```
Also check the CI JDK is ≥ 21: the AAR's classes are **class-file major 65**. `compileOptions VERSION_17` does not protect you; a JDK-17 runner fails with `bad class file ... version 65.0`. (`.github/workflows/ci.yml:23` already uses JDK 21.)

**Also required**: an `AiModule` with the *same fully-qualified name* in both `src/mlNone/java/` (no-op providers) and `src/mlGemma/java/` (real providers), because `AppComponent` lives in `src/main` and must compile in both variants. This is the main structural cost of the flavor split; there is no cheaper way to keep the F-Droid artifact clean.

### 5.3 `AndroidManifest.xml`

Next to lines 18–19:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
Inside `<application>` (only reached when the GPU backend is opted in; `<uses-native-library>` is API 31+ and is an ignored unknown element below that):
```xml
        <uses-native-library android:name="libvndksupport.so" android:required="false" />
        <uses-native-library android:name="libOpenCL.so"      android:required="false" />
```
New service, in `src/mlGemma/AndroidManifest.xml` so the `mlNone` artifact never declares it:
```xml
        <service
            android:name=".ai.download.AiModelDownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```
WorkManager 2.9+ declares its own `SystemForegroundService` with `foregroundServiceType="dataSync|..."`, so nothing else is needed for the worker — but the **permission** above is still mandatory or `startForeground` throws `SecurityException` on API 34+. Call `setForegroundAsync(new ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))` (3-arg form, mandatory on 34+), guarded by `Build.VERSION.SDK_INT >= 34` to keep `lint`'s `NewApi`/`InlinedApi` happy (`abortOnError true` at `build.gradle:95`).

Unrelated but should be fixed in the same PR: `DownloadWebPageService`'s `<service>` at `AndroidManifest.xml:209-212` has no `foregroundServiceType`, so `startForeground` at `DownloadWebPageService.java:84` already throws `MissingForegroundServiceTypeException` on targetSdk 34+. Add `android:foregroundServiceType="dataSync"` there too.

### 5.4 `News-Android-App/proguard-rules.pro` (append)

Both AARs ship **no `proguard.txt`** (verified: `unzip -l | grep -i proguard` is empty for all three), and `gradle.properties:33` sets `android.r8.strictFullModeForKeepRules=true`, so keeping an interface does **not** keep its implementers. Debug builds pass; the release build crashes at runtime.

```proguard
###############
# LiteRT-LM 0.15.0 — the AAR ships NO consumer rules. Native code does FindClass/GetMethodID
# on these names (strings found in liblitertlm_jni.so).
-keep class com.google.ai.edge.litertlm.LiteRtLmJni { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$* { *; }
-keep class com.google.ai.edge.litertlm.NativeLibraryLoader { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.BenchmarkInfo { *; }
-keep class com.google.ai.edge.litertlm.InputData { *; }
-keep class com.google.ai.edge.litertlm.InputData$* { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { *; }
-keep class com.google.ai.edge.litertlm.Message { *; }
-keep class com.google.ai.edge.litertlm.Content { *; }
-keep class com.google.ai.edge.litertlm.Content$* { *; }
-keep class com.google.ai.edge.litertlm.Contents { *; }
-keep class com.google.ai.edge.litertlm.SamplerConfig { *; }
-keep class com.google.ai.edge.litertlm.ThinkingConfig { *; }
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keep class com.google.ai.edge.litertlm.Backend$* { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat$* { *; }
-keep interface com.google.ai.edge.litertlm.MessageCallback { *; }
-keep interface com.google.ai.edge.litertlm.ResponseCallback { *; }
# strictFullMode: our own implementers must be kept explicitly
-keep class de.luhmer.owncloudnewsreader.ai.** implements com.google.ai.edge.litertlm.MessageCallback { *; }
-dontwarn kotlin.reflect.**
-keep class kotlin.Metadata { *; }

###############
# MediaPipe Tasks 1.0.0 — also ships NO consumer rules. AutoValue + protolite + JNI.
-keep class com.google.mediapipe.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.flogger.**
-dontwarn com.google.android.datatransport.**

###############
# Gson DTOs for the model catalogue
-keep class de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry { *; }
-keep class de.luhmer.owncloudnewsreader.ai.model.AiPartMeta { *; }

# NOTE: -keepattributes APPENDS across rule files, so this does not disturb line 75.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
```
This cannot be validated by compiling. It needs `./gradlew :News-Android-App:assembleOssMlGemmaRelease` plus an on-device smoke run — put that in the definition of done for the first AI PR, not at the end of the project.

---

## Pushback

Where the product model-management UX is not implementable as specified.

**1. The entire gated-Gemma flow (§4/S2, AC #4, #6, #7) is built on a wrong premise.**
`gemma-4-E2B` — the model the spec picks as the T2 default — is **not gated**. Anonymous download returns 302 → 200 (verified). The browser-accept dialog, the `licence_ack_<repo>` flag, and the "I've accepted — retry" button never fire for it. Meanwhile the models that *are* gated return **401 `GatedRepo`**, not 403, with `www-authenticate: Bearer` — and browser acceptance alone does not unblock an anonymous request. A token is mandatory. So the spec has (a) a licence flow for a model that needs none, and (b) hides the one control that gated models actually require behind a "second consecutive 403" that will never occur. Rewrite S2 as §2.4 above. This also settles product open item **R4**.

**2. Row 4 `sp_ai_model_embedding` cannot be a `ListPreference`.**
There is one embedder, it comes from a different CDN in a different file format under a different download regime, and there is no second entry to ever populate the list — `TextEmbedderOptions` exposes no dimension setter, so there is no MRL/dimension choice either. A perpetually single-entry, perpetually disabled `ListPreference` reads as broken. Make it a **non-clickable status `Preference`**: title "Similarity model", summary "EmbeddingGemma · 175 MB · installed" / "Not installed — tap Download models". It still does the job the spec wanted it to do (explaining the 175 MB download); it just isn't a picker.

**3. Rows 5/6/7 (`sp_ai_model_digest`, `_enrich`, `_learn`) are three pickers for a sentinel that is correct essentially always.**
Their default is `__same_as_triage__` or `__off__`, and the spec's own framing decision is "exactly one LLM resident, ever". Three `ListPreference`s whose only realistic values are two sentinels is settings-screen noise on a phone. Collapse to: one `SwitchPreference` `cb_ai_enrich_enabled` ("Article summaries — reads the full article; slow, charger only") and one `SwitchPreference` `cb_ai_learn_enabled`, both T2-only, both implying `__same_as_triage__`. Keep the per-stage *keys* in `SettingsActivity` so a power user can set them via a future advanced screen and so the resolver logic is unchanged — just don't ship three pickers.

**4. The S7 45-second smoke-load watchdog will produce false BROKEN verdicts.**
`Engine.initialize()` is documented at "up to 10 s", but that is with a warm `cacheDir`. The **first** load of a 2.588 GB model writes the rearranged-weight / XNNPACK program cache from scratch; on a mid-range phone that is plausibly 60–150 s. A 45 s watchdog would mark a perfectly good `gemma-4-E2B` as "doesn't work with this version of the app" and offer `[Delete]` — after a 2.6 GB download. **Raise to 180 s**, run it inside the download foreground service with its own progress line ("Preparing model…"), and record the measured load time in `AI_META` so the second-run watchdog can be tightened to `3 × measured`.

**5. `DOWNLOAD_OK(m) := free >= size * 1.15 + 250 MB` guards the wrong volume and over-charges by 390 MB.**
`.part` → final is a `renameTo` on the *same* filesystem, not a copy, so no 1.15× headroom is needed on the external volume: `size + 256 MB` is correct. What the rule *misses* is that the smoke load and every subsequent `initialize()` write the compiled cache to **internal** `getCacheDir()`, a different volume, with a footprint we have not measured. Add a second, separate pre-flight on internal free space (provisionally 500 MB) — that is the check that will actually fire on a full phone, and the spec has no equivalent. On E2B the current rule demands 3.23 GB free where 2.84 GB suffices; on a 4 GB-free device that is a false rejection.

**6. `cb_ai_gpu_backend` defaulting to `true` on T2 is an untested default on the highest-blast-radius switch.**
We have zero LiteRT GPU numbers for any model on any device, and the only GPU measurements we do have (the embedder: 1445 ms init, 762 MB RSS, vs 24.9 ms / 123 MB on CPU) point the wrong way. GPU-delegate OOM on the LLM would present as "the AI folder is permanently empty" on exactly the flagship devices this feature is aimed at. Default `false` on all tiers; flip after the device spike, with data.

**7. AC #3's "Resume, not Restart" is under-specified in a way that will fail in the field.**
The HF CDN URL is signed with `Expires=` roughly 15 minutes out. If the implementation persists the redirect target (the natural thing to do), every resume after a lunch break 403s from CloudFront and the UI reports a corrupt download. Persist only the `resolve/main/...` URL, re-resolve every time. And `If-Range: <etag>` cannot be used — the CDN's `etag` is the xet content hash, which differs from the origin's `x-linked-etag` (the SHA-256). Validate resumes with `x-linked-size` + the final SHA-256 instead. Both facts verified this session.

**8. AC #20's measured budget gate uses models that will not be the defaults.**
It specifies `gemma-4-E2B on GPU` for D-high and `Gemma3-1B-IT` for D-low. Per §3 the T1 default becomes `qwen3-0.6b-int4` (ungated) and GPU is off by default. Restate as: D-high `gemma-4-E2B` **on CPU**, 30 articles ≤ 5 min, post-repair parse failure < 5 %; D-low `qwen3-0.6b-int4` on CPU, 15 articles ≤ 5 min, post-repair failure < 15 %. The escape hatch the spec already wrote — demote T1 to "embeddings/centroids only" if it misses — remains exactly right, and is the correct product answer rather than a smaller model.

**9. "Not supported on this device (needs a 64-bit CPU, 3 GB RAM, Android N+)" understates the gate.**
The binding constraint is `arm64-v8a` specifically, not "64-bit": the AAR ships `arm64-v8a` and `x86_64` only. A 64-bit x86 Android device (rare, but real: some Chromebooks, some Android-x86 installs) passes "64-bit" and then hits `UnsatisfiedLinkError`. Gate on `Build.SUPPORTED_64_BIT_ABIS` **containing `arm64-v8a`**, and say "needs a 64-bit ARM processor".

---

## Open, with the test that resolves each

| # | Unknown | Blocks | Resolving test |
|---|---|---|---|
| S1 | Actual resident RSS and tokens/s for `gemma-4-E2B` and `qwen3-0.6b-int4` on a real phone | every `minTotalRamBytes` value, the T1/T2 boundary, `sp_ai_batch_budget` defaults, AC #20 | `Debug.getMemoryInfo()` + `adb shell dumpsys meminfo` around a 30-article run on D-high and D-low |
| S2 | Does `ResponseFormat.regex("(?:…\\n){4}")` work end-to-end on E2B? | whether the constrained path is the default or a bonus | the §2.7 smoke-load's second half, run on device once |
| S3 | Does `TextEmbedderGraph` honour `Delegate.GPU`/`Delegate.NPU`? | 169 ms → 18 ms on the embed stage, i.e. whether a 400-article backfill is 4 min or 30 s | one run with `setDelegate(Delegate.GPU)`; check whether graph init errors |
| S4 | Does `embed(text, CTX)` prefix once or twice? | silent embedding-quality loss with no symptom | `embed("x", CTX)` vs `embed("task: clustering \| query: x")`, compare `floatEmbedding()` element-wise |
| S5 | Does `tasks-text` still initialise with `transport-backend-cct` excluded? | the F-Droid/`oss` distribution story | build `mlGemma` with the exclude, call `TextEmbedder.createFromOptions` on device, watch for `NoClassDefFoundError` |
| S6 | First-load wall time for E2B with a cold `cacheDir` | the smoke-load watchdog value (Pushback #4) | time `Engine.initialize()` twice, cold cache then warm |