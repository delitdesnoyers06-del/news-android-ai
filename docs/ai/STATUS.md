# On-device AI triage — implementation status

Port of the `veille` tech-watch workflow (Python/FastAPI + Bedrock) into this Android reader,
running entirely on-device with Gemma via LiteRT-LM.

Read `PLAN.md` for the 31 decisions and the architecture. Read `PHASE0_RESULTS.md` for the build
groundwork. `IMPL_*.md` are the per-phase implementation reports (deviations and open items are
recorded there honestly — read them before extending a phase).

---

## Where it stands

| Phase | Scope | State |
|---|---|---|
| 0 | minSdk 24, `ml` flavor split, pinned deps, R8 rules, manifest, CI | **done, verified** |
| 1 | AI SQLite schema (10 tables) + 9 stores + GC | **done, verified** |
| 2 | "For you" virtual folder + the list SQL | **done, verified** |
| 3 | More/Less-like-this signal, undo, star seeding | **done, verified** |
| 4 | Embeddings, centroids, prefilter, rank — the taste model | **done, verified** |
| 5 | Model catalogue, download/resume, settings, model manager UI | **done, verified** |
| 6 | LiteRT-LM engine, prompts, scoring, repair ladder | **done, code-verified only** |
| 7 | Digest (theme grouping, abstract, digest screen) | **done, code-verified only** |
| 8 | Interests note, taste drafting, diff+approve, history/revert | **done, code-verified only** |
| 9 | Hardening, run policies, on-device validation | **not started** |

"Verified" = builds green and asserted by tests running on real SQLite.
"Code-verified only" = compiles, is covered by tests against fakes, and **has never run against a
real model**.

## Verification, as measured

```
./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest \
          :News-Android-App:testOssMlNoneDebugUnitTest \
          :News-Android-App:assembleOssMlNoneDebug \
          :News-Android-App:assembleDevMlGemmaDebug \
          :News-Android-App:assembleOssMlGemmaRelease \
          detekt spotlessCheck :News-Android-App:lint
=> BUILD SUCCESSFUL

testOssMlGemmaDebugUnitTest   tests=350 failures=0 errors=0
testOssMlNoneDebugUnitTest    tests=344 failures=0 errors=0
```
R8 full-mode release (`assembleOssMlGemmaRelease`) is now part of the same run, not a separate
check. The two counts differ by six because `DataTransportStubTest` lives in `src/testMlGemma` —
it drives MediaPipe's real classes, which `mlNone` does not have.

| APK | Bytes |
|---|---|
| `dev-mlNone-debug` (no AI) | 15,658,103 |
| `dev-mlGemma-debug` (AI, both ABIs) | 90,441,109 |

Flavor isolation, measured on the built `oss-mlNone-debug` APK: **0** `.so` files, and across all
18 dex files **0** references to `com/google/ai/edge/litertlm`, `com/google/mediapipe`,
`androidx/work` or `com/google/android/datatransport`. (The bare string `"litertlm"` does appear
once — it is the engine cache subdirectory name in `AiEngineManager`, which is in `src/main`. It is
a directory name, not a class reference.) `mlGemma` ships `arm64-v8a` + `x86_64` only, and its
release dex retains all nine `com.google.android.datatransport` no-op stubs.

## What veille semantics survived the port

- The **prefilter is the whole point**: the expensive stage only ever sees ~30 items regardless of
  how many feeds you add. That is what makes this viable on a phone at all.
- `sim = cos(v, likedCentroid) − cos(v, rejectedCentroid)`, a missing centroid contributing 0, and
  cold-start falling back to recency with undated articles **last**.
- `rank = eff + 0.5·sim`, the 0.5 chosen so similarity can never cross a score tier while a feed
  weight can.
- `NULL` vs `0` is load-bearing in two places: `LLM_SCORE` (never judged / judged off-topic) and
  `SIM_SCORE` (never embedded / embedded but cold).
- **The pipeline never writes a human word.** Machine status lives in `AI_SCORE.STATUS`
  (queued|selected|discarded); human judgement lives in `AI_TASTE.STATE` (kept|rejected).
  `AI_DECISION` is append-only — undo appends a row, it never deletes one.
- Never lose an article: unparseable output → one repair turn → split to batch-of-1 → `LLM_SCORE`
  NULL. Asserted by a test that feeds the pipeline pure garbage and requires every article in to
  produce a row out.
- The anti-collapse guard on taste learning, and the rule that **no code path writes the interests
  note from a model response without an explicit user tap**.

## Known open items

1. **Nothing has ever talked to a real model.** Phases 6–8 are covered against fakes. `Engine.initialize()`
   has never been called. This is the single biggest caveat on the whole feature.
2. **Spikes S1–S4, S6 and S8 need physical hardware** — RAM/throughput per model, whether
   `ResponseFormat.regex` works on E2B, GPU delegate support, the embed-prefix question (S4, which
   silently degrades quality if wrong) and cold load time.
   **S5 is closed**: `TextEmbedder` init without datatransport works. The exclusion is kept and
   `src/mlGemma/java/com/google/android/datatransport/` supplies the excluded API as no-ops;
   `DataTransportStubTest` runs MediaPipe's real `RemoteLoggingClient` and
   `TasksStatsLoggerFactory` against them, and `TextEmbedder.createFromOptions` now gets all the
   way to `UnsatisfiedLinkError: no mediapipe_tasks_jni` — i.e. past the whole logging path —
   instead of `NoClassDefFoundError: …TransportRuntime`. Everything after the JNI boundary is
   still device-only. See `DEEPREVIEW.md`.
3. **S7 (R8 keep rules) is only half-closed.** The release build compiles; only an on-device run
   proves nothing JNI-reachable got stripped.
4. **The download has never run against the real CDN.** Resume/206/`x-linked-size` are unit-tested at
   the sidecar level only. A MockWebServer test of the transfer loop is the cheapest next test.
5. Run policies (Wi-Fi-only, battery floor, thermal pause) render in settings but are **not enforced**.
6. The status strip, onboarding card, empty states and the per-row "why" line are not built.
   `AiDegrade` computes the state; nothing renders it.
7. `AI_META['feed_weight_map']` is unread — `feedBump` is 0 everywhere, so per-feed weighting is inert.
8. Phase 9 (hardening) not started.
