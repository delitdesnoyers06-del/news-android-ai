# EMBEDDING HALF — VALIDATION REPORT

**Headline: the embedding path is NOT broken. The brief's open question resolves in favour of the plan — but not via HuggingFace.** Two brief assumptions are wrong (`litert-community` HF repo is unusable; `armeabi-v7a` is dead) and one flagged risk (native symbol conflict) is refuted with binary evidence.

All artifact claims below were verified by downloading and parsing the actual binaries, not by reading docs.

---

## 1. What does `litert-community/embeddinggemma-300m` ship? — **CRITICAL**

**CLAIM:** HF `litert-community/embeddinggemma-300m` ships a MediaPipe-loadable `.task`.
**VERDICT: REFUTED — but the design survives. Use a different, better source.**

**EVIDENCE**
- `https://huggingface.co/litert-community/embeddinggemma-300m/tree/main` contains **33 `.tflite` files + `sentencepiece.model` (4.68 MB) + README. Zero `.task`, zero `.litertlm`.** Variants are `embeddinggemma-300M_seq{256,512,1024,2048}_mixed-precision[.{google.tensor_g5,mediatek.mt6991,mt6993,qualcomm.sm8550,sm8650,sm8750,sm8850}].tflite`, 176–248 MB each.
- The repo is **GATED**: `curl .../raw/main/README.md` → `Access to model litert-community/embeddinggemma-300m is restricted. You must have access to it and be authenticated.`
- A bare `.tflite` cannot be handed to `TextEmbedder`: `libmediapipe_tasks_jni.so` requires a bundle containing a `sentencepiece.model` (strings `sentencepiece.model`, `sentencepiece.ModelProto` are in the shipped `.so`).

**THE ACTUAL PATH (verified end to end):** MediaPipe publishes the bundle itself, ungated, on its own model CDN, and **EmbeddingGemma is an officially documented Text Embedder model**, not a test artifact.

```
https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task
```
- `HTTP 200`, `content-length: 183816181` (175.3 MiB), `last-modified: 2026-06-23`. No auth, no Gemma-terms gate. Linked from `developers.google.com/edge/mediapipe/solutions/text/text_embedder`.
- I downloaded and unzipped it. It is a plain ZIP with **exactly 3 entries** (all STORED, uncompressed):
  | entry | bytes |
  |---|---|
  | `gemma.tflite` | 179,132,472 |
  | `manifest.pb` | 50 |
  | `sentencepiece.model` | 4,683,319 |
- `manifest.pb` decoded: `b'\n\nGoogle LLC\x12\x1dtext_embedder_embedding_gemma\x1a\x05gemma'`
- I parsed the flatbuffer of `gemma.tflite` directly: **1 subgraph `main`, 2619 tensors, input `embed_512_text_batch:0` shape `[1, 512]` INT32, output `StatefulPartitionedCall:0` shape `[1, 768]` FLOAT32.**
- Tokenizer is genuinely multilingual: `sentencepiece.model` is 4.68 MB with **31,003 CJK-range and 44,734 Cyrillic-range UTF-8 sequences** (vs Gecko's 794 KB / 1,151 / 150).

**IMPACT ON PLAN**
- Drop `litert-community/embeddinggemma-300m` from the plan entirely for embeddings. It is gated, has no bundle, and buys nothing.
- The model-manager Settings screen therefore has **two different download regimes**: LLM `.litertlm` from HF (gated → 403 → browser-accept-terms flow, Gallery pattern), embedder `.task` from `storage.googleapis.com/mediapipe-models/...` (plain GET, no auth, no gate). Do not build one generic HF downloader and assume it covers both.
- Budget **175 MiB** for the embedder download, on top of the LLM.
- ⚠️ **The `[1,512]` shape is STATIC.** Every embedding pads to 512 tokens, so a 40-word headline costs the same as a full abstract. There is no short-text fast path. See item 5.

**Optional optimisation (needs the gated repo):** you can build your own `.task` — it is just `zip -0` of `gemma.tflite` + `sentencepiece.model` + the 50 manifest bytes above (hex `0a 0a 47 6f 6f 67 6c 65 20 4c 4c 43 12 1d 74 65 78 74 5f 65 6d 62 65 64 64 65 72 5f 65 6d 62 65 64 64 69 6e 67 5f 67 65 6d 6d 61 1a 05 67 65 6d 6d 61`). Swapping in `embeddinggemma-300M_seq256_mixed-precision.tflite` renamed to `gemma.tflite` would cut CPU latency ~2.5x (169→66 ms). `gecko.task` has **no `manifest.pb` at all** (2 entries only), so the manifest is likely optional. **Uncertain:** whether `TextEmbedderGraph` keys off the inner filename — the shipped `.so` contains both `gecko.tflite` and `gemma_tflite` string literals. Resolution: build the bundle, name the tflite `gemma.tflite`, keep the manifest verbatim, and run `TextEmbedder.createFromFile` on a device. Treat as a phase-2 optimisation, not a dependency.

---

## 2. Artifact, version, minSdk, APK cost

**VERDICT: CONFIRMED, with two corrections to the brief.**

**EVIDENCE** (downloaded from `dl.google.com/dl/android/maven2`)

```gradle
implementation("com.google.mediapipe:tasks-text:1.0.0")   // pulls tasks-core:1.0.0 transitively
```
`maven-metadata.xml` → `<release>1.0.0</release>`, `lastUpdated 20260727204405`. (Version line jumped `0.10.35` → `1.0.0`; `0.20230731` is a stale outlier — do not use `latest.release`, pin `1.0.0`.) POM deps: `androidx.annotation:1.1.0`, `guava:27.0.1-android`, `tasks-core:1.0.0`.

| artifact | AndroidManifest `minSdkVersion` | native lib | ABIs | arm64 on-disk | arm64 compressed |
|---|---|---|---|---|---|
| `tasks-text:1.0.0` | **24** (target 34) | `libmediapipe_tasks_textgenai_jni.so` | v7a, arm64, x86, x86_64 | 14.44 MB | 6.67 MB |
| `tasks-core:1.0.0` | **24** (target 34) | `libmediapipe_tasks_jni.so` | v7a, arm64, x86, x86_64 | 11.02 MB | 4.93 MB |
| `litertlm-android:0.15.0` | **24** | `liblitertlm_jni.so` | **arm64-v8a, x86_64 ONLY** | 21.20 MB | 9.23 MB |

**IMPACT ON PLAN**
- **minSdk 21 → 24 is mandatory and non-negotiable** (manifest merger will hard-fail otherwise). All three AARs agree on 24, so this is one bump, not three. Confirms brief PART D item 3; the answer is **24**.
- **LiteRT-LM ships no `armeabi-v7a`.** 32-bit-only ARM devices cannot run the LLM at all. Set `ndk { abiFilters "arm64-v8a" }` (+ `"x86_64"` if you want the emulator) — otherwise you ship 50 MB of AARs' worth of unusable v7a/x86 MediaPipe libs. This resolves brief PART D item 4: **abiFilters is required, not optional.**
- **Free 6.67 MB:** `libmediapipe_tasks_textgenai_jni.so`'s only 8 exported symbols are `Java_..._TextSummarizer_*` and `Java_..._TextProofreader_*`. `TextEmbedder.class`'s constant pool references `mediapipe_tasks_jni` (tasks-core), **not** textgenai. So exclude it:
  ```gradle
  packaging { jniLibs { excludes += "**/libmediapipe_tasks_textgenai_jni.so" } }
  ```
  Verify once on device that `TextEmbedder.createFromFile` still initialises.
- **Net APK cost of the embedding half, arm64-only, textgenai excluded: ~6.3 MB compressed** (4.93 MB `.so` + 1.34 MB `tasks-core` classes.jar + 62 KB `tasks-text` classes.jar). Guava 27 is already a fat transitive dep — check for a conflict with the existing dependency graph.
- R8 full mode is on (`android.r8.strictFullModeForKeepRules=true`) and these classes are AutoValue + JNI-reflected. Expect to need keep rules for `com.google.mediapipe.**`.

---

## 3. TextEmbedder API — verified against the *shipped* 1.0.0 bytecode, not the docs

**VERDICT: CONFIRMED in full, including `cosineSimilarity` being `public static`. Fully Java-callable, no Kotlin needed.**

**EVIDENCE** — `javap -public` on `tasks-text-1.0.0.aar!/classes.jar`:

```java
public final class TextEmbedder implements AutoCloseable {
  public static TextEmbedder createFromFile(Context, String);
  public static TextEmbedder createFromFile(Context, File) throws IOException;
  public static TextEmbedder createFromOptions(Context, TextEmbedder$TextEmbedderOptions);
  public TextEmbedderResult embed(String);
  public TextEmbedderResult embed(String, TextEmbedder$TextFormatContext);
  public void close();
  public static double cosineSimilarity(Embedding, Embedding);   // <-- public static, confirmed
}
public abstract class TextEmbedder$TextEmbedderOptions$Builder {
  public abstract Builder setBaseOptions(BaseOptions);
  public abstract Builder setL2Normalize(boolean);   // default false
  public abstract Builder setQuantize(boolean);      // default false
  public abstract TextEmbedderOptions build();
}
// com.google.mediapipe.tasks.components.containers.Embedding
  public static Embedding create(float[], byte[], int, Optional<String>);   // <-- synthesize centroids
  public abstract float[] floatEmbedding();
  public abstract byte[]  quantizedEmbedding();
  public abstract int     headIndex();
// TextEmbedderResult.embeddingResult() -> EmbeddingResult.embeddings() -> List<Embedding>
// BaseOptions$Builder: setModelAssetPath/FileDescriptor/Buffer, setDelegate(Delegate{CPU,GPU,NPU})
```
`createFromFile(Context, String)` takes an **asset** path; use `setModelAssetPath` semantics carefully — for a runtime-downloaded 175 MB file you want `createFromOptions` with `setModelAssetPath(absolutePath)` or `setModelAssetFileDescriptor`. No `createFromBuffer`.

**IMPACT ON PLAN**
- `Embedding.create(float[], byte[], int, Optional<String>)` is public static → you *can* wrap a computed centroid into an `Embedding` and call `TextEmbedder.cosineSimilarity`. **Don't.** Veille needs `cos(v,liked) − cos(v,rejected)`, and `cosineSimilarity` throws `IllegalArgumentException` on zero L2-norm and type mismatch. Write a 6-line `dot(unitA, unitB)` in Java, port `_unit()` from `backend/app/ai/embed.py:59` verbatim (returns `null` for zero-norm), and keep the sentinel semantics (`sim_score` NULL = never embedded, `0.0` = embedded but no decisions) that the brief flags as load-bearing.
- Set `setL2Normalize(true)` and drop your own normalisation of the article vector; you still must unit-normalise the **centroid** yourself after averaging.
- Do **not** use `setQuantize(true)`: it returns `byte[]` and you lose the ability to average into a float centroid cheaply. Quantize yourself at the storage layer if you need to (item 8).
- `TextEmbedder` is `AutoCloseable` and Java-friendly. Nothing here forces Kotlin. (LiteRT-LM's `Flow` API does — that's the *other* half's problem.)

---

## 4. EmbeddingGemma specifics: prefixes and Matryoshka

**CLAIM A — task prefixes are required and getting them wrong silently degrades quality.**
**VERDICT: TRUE, AND MEDIAPIPE HANDLES IT FOR YOU — but only if you use the 2-arg `embed()`.** This is a real silent-quality-loss trap.

**EVIDENCE** — string constants extracted from the shipped `TextEmbedder.class`: `"task: "`, `" | query: "`, `"title: "`, `" | text: "`, `"none"`, `"search result"`, `"sentence similarity"`, `"classification"`, `"clustering"`, `"question answering"`, `"fact checking"`, `"code retrieval"`, plus method `getGeckoEmbeddingText(String, TextFormatContext)` and `getTaskString(EmbeddingType)`. Official docs confirm: *"This model handles input differently based on the task type... For the query formats, the template follows `task: <task> | query: <text>`, and for document formats, `title: <title> | text: <text>`. The formats follow the official EmbeddingGemma prompt instruction."* Matches `google/embeddinggemma-300m`'s published prefixes exactly.

`TextEmbedder.EmbeddingType` = `{RETRIEVAL_QUERY, RETRIEVAL_DOCUMENT, SEMANTIC_SIMILARITY, CLASSIFICATION, CLUSTERING, QUESTION_ANSWERING, FACT_CHECKING, CODE_RETRIEVAL}`; `TextRole` = `{QUERY, DOCUMENT}`; `TextFormatContext.builder()` = `setTaskType`, `setTitle`, `setRole`.

**IMPACT ON PLAN — this is the single easiest way to silently ship a broken taste model:**
- `embed(String)` (1-arg) applies **no prefix at all**. If any code path uses it, those vectors live in a different region of the space than the prefixed ones and cosine against the centroid is garbage. **Ban the 1-arg overload in review.**
- Use **one** `TextFormatContext` for every article and every centroid member, built once and reused:
  ```java
  TextEmbedder.TextFormatContext CTX = TextEmbedder.TextFormatContext.builder()
      .setTaskType(TextEmbedder.EmbeddingType.CLUSTERING)   // -> "task: clustering | query: <text>"
      .setRole(TextEmbedder.TextRole.QUERY).build();
  ```
  **Recommendation: `CLUSTERING`.** Veille's operation is not retrieval — it is "is this article in the same neighbourhood as my liked pile", i.e. clustering geometry. `SEMANTIC_SIMILARITY` is the defensible second choice; Google tunes it for sentence-pair STS, which is a different objective from group-membership. `RETRIEVAL_QUERY` (what MediaPipe's own test uses) is wrong here — there is no asymmetric query/document split.
- Persist the chosen `EmbeddingType` **and** the model id alongside every stored vector (veille's `article_embeddings.model` column, `backend/app/db.py:134`, already does the model half). Changing either invalidates every vector and every centroid. Make that an explicit migration path, not a silent drift.
- **Uncertain:** whether MediaPipe formats via the Java `getGeckoEmbeddingText` path only, or whether the C++ graph re-formats too (double-prefixing). Resolution: `embed("x", CTX)` vs `embed("task: clustering | query: x")` on device and compare `floatEmbedding()` element-wise.

**CLAIM B — Matryoshka truncation to 128 is available on-device.**
**VERDICT: PARTIALLY REFUTED. MediaPipe always returns 768; you truncate yourself.**

**EVIDENCE:** the tflite output tensor is statically `[1, 768]` FLOAT32. `TextEmbedderOptions.Builder` has **no** dimension/truncation setter (only `setBaseOptions`/`setL2Normalize`/`setQuantize`). MediaPipe's own `embed_succeedsWithEmbeddingGemma` asserts `hasLength(768)`.

MRL truncation is nothing more than *take the first N components, then re-L2-normalise*. That's 3 lines of Java. Quality cost, from `google/embeddinggemma-300m` MTEB Multilingual v2 (Mean Task): **768d = 61.15, 512d = 60.71, 256d = 59.68, 128d = 58.23.**

**IMPACT:** truncation buys you storage, never latency (the 768 is computed regardless). **Recommend keeping 768 and skipping MRL entirely** — see item 8; storage is not the binding constraint at phone article volumes. If you do truncate, 256d (−1.5 MTEB pts, 3x smaller) dominates 128d (−2.9 pts).

---

## 5. Latency and RAM. Is 200 articles/sync realistic?

**VERDICT: REALISTIC, but only with a per-sync cap and a charger/foreground policy. The brief's `prefilter_top_k` framing does NOT save you here — every candidate must be embedded *before* prefilter can rank it.**

**EVIDENCE** (two independent published sources)

MediaPipe official task benchmark, *Samsung S26 Ultra CPU, 4 threads, whole pipeline*:
| Model | CPU latency |
|---|---|
| Universal Sentence Encoder | **10 ms** |
| Embedding Gemma 300m | **200 ms** |

`litert-community/embeddinggemma-300m` card, *Samsung S25 Ultra*, mixed precision `e4_a8_f4_p4`:
| Backend | Seq | Init ms | Infer ms | Mem MB | Size MB |
|---|---|---|---|---|---|
| CPU (XNNPACK, 4t) | 256 | 17.6 | **66** | 110 | 179 |
| CPU | **512** | 24.9 | **169** | **123** | 179 |
| CPU | 1024 | 35.4 | 549 | 169 | 183 |
| CPU | 2048 | 35.8 | 2455 | 333 | 196 |
| GPU | 512 | 1445 | 119 | **762** | 179 |
| NPU | 512 | 241 | **18** | 231 | 184 |

**IMPACT ON PLAN**
- The shipped bundle is the **seq512** graph → **169–200 ms/article on a 2025/2026 flagship**. Mid-range (SD 7 Gen 3 / Dimensity 7300, 4 big-ish cores at ~60% of a flagship's per-core throughput): budget **450–700 ms/article**.
- **200 articles ≈ 90–140 s of pinned multi-core CPU on a mid-range phone.** That is a thermal event, not a background nicety. It is acceptable *once per sync* for a JobIntentService with a foreground notification (the `services/DownloadWebPageService.java` pattern the brief identifies), and only because veille's "embed once per article ever" invariant (`_stage_embed`, pipeline.py:690, `ensure_embeddings` LEFT JOINs on `article_embeddings`) means steady-state is ~40 new articles/day, not 200.
- **RAM is not a problem for the embedder** — 123 MB at seq512, well inside a normal Android heap+native budget. RAM *is* a problem if you hold the embedder and the LLM engine open simultaneously (Gemma3-1B/gemma-4-E2B is 1–3 GB). **Serialize: embed all → `close()` the TextEmbedder → open the LLM Engine → score → close.** Never overlap.
- Static `[1,512]` padding means **truncating article text below ~512 tokens gives you zero speedup.** Do it anyway for quality (veille uses `f"{title}\n{summary}"[:2000]` — ~500–600 tokens, i.e. right at/over the 512 limit and silently truncated by the tokenizer). **Cut to ~1400 chars** so you're not relying on undefined truncation behaviour.
- Concrete policy recommendation: hard cap `AI_EMBED_MAX_PER_SYNC = 60` (mirroring `prefilter_top_k`), newest-first; carry the remainder to the next sync; gate the full 200-article backfill behind charger+idle. Surface the cap in Settings.
- **NPU (18 ms) is the real prize** — `Delegate.NPU` exists in `tasks-core:1.0.0`, and `litert-community` ships per-SoC tflites (`qualcomm.sm8550/8650/8750/8850`, `mediatek.mt6991/6993`, `google.tensor_g5`) that map exactly to it. But it requires the gated repo + hand-built `.task` + per-SoC model selection (the Gallery's device-capability filter). **Phase 3, not phase 1. Uncertain** whether `TextEmbedderGraph` honours `Delegate.NPU`/`GPU` at all — MediaPipe text tasks are historically CPU-only, the docs benchmark table lists CPU only, and `tasks-text`/`tasks-core` ship no separate GPU `.so`. Resolution: one device run with `setDelegate(Delegate.GPU)` and check whether graph init errors.

---

## 6. Alternatives, ranked, on the multilinguality axis

**VERDICT: the brief's fallback ladder is wrong. Gecko is the worst option, not the second-best. USE is the right fallback and it IS multilingual.**

I downloaded and parsed every candidate.

| Rank | Model | Bundle | Dim | Max tokens | Multilingual? | CPU latency | Verdict |
|---|---|---|---|---|---|---|---|
| **1** | **EmbeddingGemma 300m** | `embedding_gemma.task`, 175 MiB, official CDN | 768 | 512 | **YES, strongly** | 169–200 ms | **Primary.** |
| **2** | **USE-QA multilingual** | `universal_sentence_encoder.tflite`, **6.12 MB**, official CDN | **100** | (string in) | **YES** | **10 ms** | **Fallback / low-end default.** |
| 3 | Gecko | `gecko.task`, 108 MiB, *test assets only* | 768 | **64** | **NO** | n/a | **Reject.** |
| 4 | MobileBERT | `mobilebert_embedding_with_metadata.tflite` | 512 | — | NO (English uncased) | — | Reject. |
| 5 | Average Word Embedding | `regex_one_embedding_with_metadata.tflite` | 16 | — | NO | — | Reject. |

**EVIDENCE for the multilinguality calls** (tokenizer vocabulary composition — count of UTF-8 byte sequences in CJK and Cyrillic ranges, which is a direct proxy for trained language coverage):

| tokenizer | size | CJK-range seqs | Cyrillic-range seqs |
|---|---|---|---|
| EmbeddingGemma `sentencepiece.model` | 4.68 MB | **31,003** | **44,734** |
| USE `universal_sentence_encoder.tflite` (embedded SP) | 6.12 MB total | **11,816** | **4,545** |
| Gecko `sentencepiece.model` | **794 KB** | **1,151** | **150** |

- USE model metadata string is literally **`"USE-QA TFLite model"`**, and I parsed its graph: **3 × `STRING` inputs** (`ParseExample/ParseExampleV2:1/2/3`), **2 outputs `[1,100]` FLOAT32** (`Final/EncodeQuery/mul`, `Final/EncodeResult/mul`). Dual encoder. A multilingual SentencePiece vocab in a 6 MB model = this is `universal-sentence-encoder-multilingual-qa`, explicitly trained for **cross-lingual** retrieval. FR and EN on the same subject *do* land near each other — that is the model's design goal.
- Gecko: input tensor is **`[1, 64]` INT32**. Sixty-four tokens. `f"{title}\n{summary}"` does not fit — you'd be embedding the headline and half the first sentence. Combined with a 32k English-centric vocab and the fact that Gecko is **not listed as a supported model** on the MediaPipe Text Embedder docs page (only EmbeddingGemma and USE are), it is a dead end.

**IMPACT ON PLAN**
- Rewrite the brief's fallback ladder to: **EmbeddingGemma → USE-QA. Delete Gecko.**
- USE is a *genuinely attractive* default for the "low-end device" tier in the model-manager Settings screen: **6.12 MB vs 175 MiB, 10 ms vs 200 ms, 20x smaller embeddings (100d vs 768d), still cross-lingual.** For a like/dislike centroid — a coarse, 2-class geometric decision, not fine-grained retrieval — 100 dims is very likely sufficient. Ship it as the default and offer EmbeddingGemma as the "better quality" upgrade; that inverts the usual framing but matches the actual cost curve.
- **Uncertain:** USE has *two* output heads (`EncodeQuery`, `EncodeResult`) and *three* string inputs. Which head MediaPipe's `TextEmbedder` returns, and what it puts in inputs 2/3, is not documented. Since the veille comparison is symmetric (article vs centroid-of-articles), you need both sides on the same head — which you get for free if `TextEmbedder` always picks the same one. Resolution: on device, `embed("a")` and check `embeddings().size()` and `headIndex()`. If it returns 2 embeddings, pick index 0 consistently and document it.
- USE takes **no** `TextFormatContext` (prefix formatting is EmbeddingGemma/Gecko-specific), so your abstraction layer must make the format context optional per model.

---

## 7. Can MediaPipe tasks-text and LiteRT-LM coexist in one APK?

**VERDICT: REFUTED as a risk. They coexist cleanly. This is not a blocker and does not need a spike.**

**EVIDENCE** — I parsed the ELF `.dynsym` of all three arm64 `.so` files and counted globally-bound, defined symbols:

| library | distinct filename | exported dynamic symbols | TFLite/XNNPACK symbols exported |
|---|---|---|---|
| `libmediapipe_tasks_textgenai_jni.so` | ✔ | **10** | **0** |
| `libmediapipe_tasks_jni.so` | ✔ | **101** | **0** |
| `liblitertlm_jni.so` | ✔ | **30** | **0** |

Every single exported symbol is either a `Java_*` JNI entry point or a linker section marker (`__start_pb_defaults`, `__stop_google_malloc`, …). Each library statically links its own TFLite/XNNPACK with **hidden visibility**; nothing is exported to the global namespace, and Android's `System.loadLibrary` → `android_dlopen_ext` uses `RTLD_LOCAL`. There is also **no filename collision** — three distinct `.so` names, so the APK packager has nothing to dedupe or fail on.

**IMPACT ON PLAN**
- Remove "native symbol conflict" from the risk register. Add these two real costs instead:
  1. **Code duplication:** you ship 2–3 independent copies of TFLite+XNNPACK. That is exactly the ~15.9 MB (arm64, textgenai excluded) measured in item 2. Unavoidable; budget it, don't fight it.
  2. **Runtime RAM and thread contention:** each library spins up its own XNNPACK thread pool. Two engines alive at once = two pools fighting for the same 4 big cores, plus EmbeddingGemma's 123 MB on top of the LLM's 1–3 GB. **The mitigation is sequencing, not linking.** Enforce it structurally: one `AiStage` at a time, `close()` before the next opens.
- Also check for a **Guava conflict**: `tasks-text:1.0.0` pulls `guava:27.0.1-android`. Verify against whatever the existing Retrofit/OkHttp/Glide graph resolves.
- `litertlm-android:0.15.0` POM confirms the brief's warning: it hard-depends on `kotlinx-coroutines-android:1.9.0` (+ `kotlin-reflect:2.2.21`, `gson:2.13.2`). Coroutines enter the build via the LLM half, **not** the embedding half — `tasks-text` needs neither. If the embedding half ships first, it ships without coroutines.

---

## 8. Storing a 768-dim vector in SQLite; is a full scan fast enough?

**VERDICT: CONFIRMED fast enough, by a very wide margin — and the premise in the task overstates what veille does. Veille never full-scans.**

**EVIDENCE — what veille actually does** (`backend/app/db.py:134`, `backend/app/ai/embed.py`):
```sql
CREATE TABLE IF NOT EXISTS article_embeddings (
  article_id  INTEGER PRIMARY KEY REFERENCES articles(id) ON DELETE CASCADE,
  model       TEXT NOT NULL,
  dim         INTEGER NOT NULL,
  vector      BLOB NOT NULL,
  created_at  TEXT NOT NULL
);
```
`DTYPE = np.dtype("<f4")` (`embed.py:37`, pinned explicitly, not left to platform default); `pack()` = `.tobytes()`; `unpack()` = `np.frombuffer(..., count=dim).astype(np.float32)` — the `.astype` is a deliberate copy because `frombuffer` returns a read-only view.

The centroid query (`embed.py:125`) is **not** a table scan:
```sql
SELECT e.dim, e.vector FROM candidates c
JOIN article_embeddings e ON e.article_id = c.article_id
WHERE c.veille_id = ? AND c.status = ?
```
It touches only the *decided* rows (hundreds), and the result is cached on `(veille_id, decision_count)` so it recomputes only when a decision lands. Per-article scoring is a single `np.dot` against two 768-vectors on the current batch (`embed.py:214-216`). **There is no scan over tens of thousands of rows anywhere in the pipeline.**

**EVIDENCE — measured scan cost** (JVM 21, x86_64, naive scalar loop, 5 reps, 20,000 × 768):
```
float32 768d x20000:  35.3 / 94.4 / 39.3 / 15.3 / 16.9 ms   (steady state ~16 ms)
int8    768d x20000:  14.7 / 33.9 / 65.3 ms
storage: float32 768d = 58 MiB | int8 768d = 14 MiB | float32 256d = 19 MiB | float32 128d = 9 MiB
```

**IMPACT ON PLAN — concrete recommendations**
1. **Port the schema verbatim** into the separate AI DB the brief mandates (greenDAO's `DevOpenHelper.onUpgrade` DROPs everything — `database/model/DaoMaster.java:55-57`). Keep `model` and `dim` columns; **add an `embedding_type` column** for the `TextFormatContext` task type (item 4). Key on `RssItem.fingerprint`, not `RssItem.id` — ids don't survive the cache wipe, fingerprints do.
2. **Store float32 little-endian BLOB**, identical bytes to veille's `pack()`. Java: `ByteBuffer.allocate(dim*4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(vec)`. This buys you offline parity testing against the Python implementation — dump a vector from each and diff. **Do that; it is the cheapest possible correctness check on the whole embedding half.**
3. **Do not build a vector index. Do not use FTS.** The measured 16 ms desktop scan → maybe 100–400 ms on ART/mid-range for a pathological 20k full scan, done at most once per sync. Two 768-dim dot products per new article is free.
4. **Storage is the only real constraint, and it's mild.** 58 MiB at 20k articles. Mitigations in preference order: (a) **delete the embedding when the greenDAO cache evicts the article** — keeps you at the size of the visible cache, likely a few thousand rows / <15 MiB; (b) MRL-truncate to 256d → 19 MiB at −1.5 MTEB pts; (c) int8 → 14 MiB but complicates centroid averaging. **Recommend (a) alone.** Skip MRL.
5. **Do not `ATTACH` the AI DB to sort the article list by AI score.** Compute the ranked id list in Java, then feed it into the existing raw-SQL path as a literal `WHERE _id IN (...)` + `ORDER BY CASE _id ...` for the `AI_DIGEST(-14)` branch of `getAllItemsIdsForFolderSQL()` (`DatabaseConnectionOrm.java:607-635`). The list is capped at `prefilter_top_k`-scale (≤60–200 ids), which is well inside SQLite's `SQLITE_MAX_VARIABLE_NUMBER`, and it keeps `insertIntoRssCurrentViewTable(sql)` untouched. This resolves brief PART D item 1 without an ATTACH or a schema bump.

---

## Things that kill the design

**None.** Nothing found refutes the plan.

Three items are **must-fix-before-coding**, not blockers:
1. **minSdk 21 → 24.** Hard manifest-merge failure otherwise.
2. **`abiFilters "arm64-v8a"` is mandatory**, because LiteRT-LM has no `armeabi-v7a`. Decide and document that 32-bit ARM devices get no AI features (degrade to `AI_ENABLED=false`, which the brief already requires as a first-class path — engineering invariant #8).
3. **Never call the 1-arg `embed(String)`.** Silent, unrecoverable quality loss with no error and no symptom other than a taste model that never converges.

Two items are genuinely uncertain and each needs a ~1-hour device spike, not more research:
- Does `TextEmbedderGraph` honour `Delegate.GPU`/`Delegate.NPU`? (Worth 169 ms → 18 ms if yes.)
- Which USE-QA head does `TextEmbedder` return, and is the 1-arg-vs-2-arg prefixing applied once or twice?