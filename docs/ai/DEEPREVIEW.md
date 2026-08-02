# Deep review — LiteRT-LM 0.15.0 / MediaPipe tasks-text 1.0.0 API correctness

Adversarial review against decompiled AAR bytecode (`javap`) + deepwiki. Bytecode beats docs.
Findings and their resolution are tracked here.

**Status: B1, B2 and F3–F9 are all fixed.** See "Resolution" at the bottom for what each fix
proved on the JVM and what still needs a device.

## Blockers found

### B1. `EngineConfig(maxNumImages = 0)` throws — the LLM could never load
`LiteRtLlm.java` passed `Integer.valueOf(0)`. The constructor validates
`maxNumImages == null || maxNumImages > 0` and otherwise throws
`IllegalArgumentException("maxNumImages must be positive or null ...")`.
Because `open()` catches `Throwable` and maps it to `AiException.LOAD_FAILED`, this failed
**silently and permanently**: every model load, every probe, every triage/digest/taste run would
have degraded, and `AiModelProbe` would have marked every model in the catalogue as broken.
**Fix:** pass `null` (also for `visionBackend`/`audioBackend` on a text-only model).

### B2. Excluding `com.google.android.datatransport` breaks `TextEmbedder.createFromOptions`
This is **spike S5, and the answer is no**. The call chain is unconditional, with no try/catch:
`TextEmbedder.createFromOptions` -> `TaskRunner.create` -> `TasksStatsLoggerFactory.create` ->
`TasksStatsProtoLogger.<init>` -> `new RemoteLoggingClient(ctx)` ->
`TransportRuntime.initialize(ctx)`. With the artifact excluded that is a `NoClassDefFoundError`
on first use, caught and mapped to `LOAD_FAILED` — the entire embedding/similarity path dead,
silently.
**Fix:** keep the exclusion (the privacy property is the reason the mlGemma split exists) and ship
no-op stubs for exactly the excluded `com.google.android.datatransport` classes MediaPipe touches.
This is not shadowing a MediaPipe class — it is supplying the excluded library's own API as a
no-op, so the class resolves and nothing is ever transmitted.

## Confirmed correct (verified, not assumed)

- `embed(String, TextFormatContext)` **does** exist in tasks-text 1.0.0, and
  `setL2Normalize` / `setQuantize` / `setTaskType` / `setRole` / `CLUSTERING` / `TextRole.QUERY`
  are all real. The embedding contract in PLAN D12 is sound. `TextEmbedder` is closed correctly.
- LiteRT-LM surface otherwise correct: `Backend.CPU()/GPU()`, the 12-arg `ConversationConfig`,
  the 8-arg `sendMessage`, `ResponseFormat.regex` as `@JvmStatic`, `SamplerConfig`.
- `resolveResponseFormat` really does pass the schema through when no tools are registered.
- The SentencePiece-only gate on constrained decoding is handled correctly, and confirmed on-device
  by `AiModelProbe`.
- `initialize()` is off the main thread, serialized by a process-wide lock; conversations are
  per-batch and closed in `finally` — no KV-cache reuse across batches.
- The `libmediapipe_tasks_textgenai_jni.so` exclusion is safe (`TextEmbedder` binds
  `libmediapipe_tasks_jni.so`, which ships in tasks-core and is untouched).

## Other findings

| # | Severity | Finding |
|---|---|---|
| F3 | HIGH | Engine leak on load timeout: `future.cancel(true)` cannot interrupt a blocking JNI `initialize()`, so a late-completing engine is never assigned and never closed — multi-GB resident for the process lifetime, and a retry loads a second one. |
| F4 | MEDIUM | `{N}` quantifier in the score grammar is valid llguidance but **forces continuation**: a model that wants to stop early is made to hallucinate the remaining lines, EOS is masked, and an empty mask is a generation error. Also, `responseFormat` is re-passed on the repair turn, so the repair can never emit only the missing lines. Prefer `forAnyLength()`. |
| F5 | MEDIUM | Watchdog `cancel(false)` can call `conv.cancel()` after the conversation was closed; `cancelProcess()` passing its alive-check just before the close CAS would hit a freed native handle. Narrow native use-after-free. |
| F6 | LOW | `firedByWatchdog` is a plain `boolean[]` written on the watchdog thread and read on the worker thread with no happens-before edge — a timed-out call can be reported as success. |
| F7 | LOW | `setRole(TextRole.QUERY)` is **inert** for `CLUSTERING`: `getGeckoEmbeddingText` only consults the role for QA/fact-check/code-retrieval task types. The emitted prefix is always `task: clustering \| query: <text>`. The code comment claiming the role is load-bearing is wrong and would mislead. |
| F8 | LOW | `AiScoreGrammar.compileOrNull` validates with `java.util.regex.Pattern` — the wrong dialect; it proves nothing about Rust-regex/llguidance acceptance. |
| F9 | LOW | `AiDigestWorker.onStopped()` does not call `AiEngineManager.shutdownNow`, unlike the other two workers. |

---

## Resolution

| # | Fix | Where | Proof |
|---|---|---|---|
| B1 | `maxNumImages` = `null`; `visionBackend`/`audioBackend` = `null` (text-only model). Constraint quoted in a comment with the bytecode offsets so nobody tidies it back to `0`. | `mlGemma/.../impl/LiteRtLlm.java` | `javap -c EngineConfig`: offsets 101-145 throw unless `null` or `> 0`. Params 3-4 carry no `checkNotNullParameter`, so `null` is legal for them. Compile-only — no engine has ever been loaded. |
| B2 | **Exclusion kept.** No-op stubs for the nine `com.google.android.datatransport` types MediaPipe touches, in `mlGemma` only. | `mlGemma/java/com/google/android/datatransport/**` + `proguard-rules.pro` | Surface derived from bytecode: `grep -rla datatransport` matches exactly one of tasks-core's 778 classes (`RemoteLoggingClient`) and zero of tasks-text's. `DataTransportStubTest` (Robolectric, mlGemma-only) constructs MediaPipe's real `RemoteLoggingClient`, runs the real `TasksStatsLoggerFactory.create`, and calls the real `TextEmbedder.createFromOptions` — which now fails with `UnsatisfiedLinkError: no mediapipe_tasks_jni` instead of `NoClassDefFoundError: …TransportRuntime`, i.e. it runs *past* the whole datatransport path. |
| F3 | Loader publishes the engine into a slot; timeout path and loader both drain it with `getAndSet`, so a late engine is closed exactly once. | `AiEngineManager.awaitLoad` | `AiEngineLoadWatchdogTest`: a fake loader that overruns the deadline by 6x; the engine's close counter goes to exactly 1. |
| F4 | `forAnyLength()` is the generation-path default; `send()` takes a per-call `AiResponseFormat`; the repair turn runs with `NONE`. Parser stays authoritative. | `AiScorer`, `AiConversation`, `AiResponseFormat`, `LlmCall`, `LiteRtConversation` | `AiScorerResponseFormatTest`. Passing `null` for the response format is legal even with `enableResponseFormat=true` — `sendMessage` only throws on a *non-null* format (offsets 12-33) and `resolveResponseFormat` returns on null (offsets 0-5). |
| F5 | `cancel()` and `close()` hold one monitor; `close()` latches a flag so a late watchdog cancel is a no-op. `send()` deliberately does not take the monitor. `LlmCall` now uses `wd.cancel(true)`. | `LiteRtConversation`, `LlmCall` | Reasoned + code-verified. Not reproducible on the JVM: the bug is a native use-after-free. |
| F6 | `firedByWatchdog` is an `AtomicBoolean`. | `LlmCall` | — |
| F7 | Comment corrected: `setRole(QUERY)` is inert for `CLUSTERING` and is set only because the AutoValue builder requires it. The prefix question is flagged as open spike S4. | `mlGemma/.../impl/MediaPipeEmbedder.java` | — |
| F8 | `compileOrNull` → `compileAsJavaRegexOrNull`, with a comment spelling out that Java's dialect proves nothing about Rust-regex/LLGuidance. | `AiScoreGrammar`, `AiModelProbe` | — |
| F9 | `onStopped()` calls `AiEngineManager.shutdownNow`. | `mlGemma/.../work/AiDigestWorker.java` | — |

### Still needs hardware

Everything the review could not settle is unchanged: no engine has ever been `initialize()`d
(open item 1 in `STATUS.md`), and B1's fix is only "the constructor no longer throws by
construction". For B2, the JVM proof stops at the JNI boundary — the graph, the delegate and the
real `embedding_gemma.task` are still spikes S1/S4/S8. F5 is a native race that no unit test can
observe.
