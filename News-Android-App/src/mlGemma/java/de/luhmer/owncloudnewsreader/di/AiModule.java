package de.luhmer.owncloudnewsreader.di;

import dagger.Module;

/**
 * mlGemma variant of the on-device AI Dagger module.
 *
 * <p>Empty at Phase 0 — the bindings arrive with the code they provide. This file exists now so the
 * two-source-set arrangement is proven by the build rather than discovered later: {@link
 * AppComponent} lives in {@code src/main} and must compile in both {@code ml} flavors, so {@code
 * AiModule} has to exist at this exact fully-qualified name in each flavor source set (see
 * docs/ai/PLAN.md D10).
 *
 * <p>What lands here in later phases (docs/ai/PLAN.md §4.6):
 * <ul>
 *   <li>{@code AiEngineManager} — refcounted, at most one {@code Engine} <em>or</em> one {@code
 *       TextEmbedder} resident at a time. Deliberately <em>not</em> a {@code @Provides Engine}: a
 *       {@code @Singleton} has no lifecycle, so nothing would ever {@code close()} multiple GB of
 *       mmapped weights, and {@code NewsReaderApplication.onCreate} runs in every process
 *       ({@code :downloadWebPageProcess}, {@code :remote}) — that would be one engine per process.
 *   <li>The store layer ({@code AiScoreStore}, {@code AiEmbeddingStore}, {@code AiDecisionStore},
 *       {@code AiCentroidStore}, …) and {@code AiModelRepository}.
 * </ul>
 *
 * <p>The mlNone twin lives at
 * {@code src/mlNone/java/de/luhmer/owncloudnewsreader/di/AiModule.java}.
 */
@Module
public class AiModule {
}
