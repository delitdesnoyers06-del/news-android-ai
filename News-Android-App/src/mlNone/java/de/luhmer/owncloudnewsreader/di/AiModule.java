package de.luhmer.owncloudnewsreader.di;

import dagger.Module;

/**
 * mlNone variant of the on-device AI Dagger module: deliberately empty.
 *
 * <p>{@link AppComponent} lives in {@code src/main} and therefore has to compile in both {@code ml}
 * flavors, which means {@code AiModule} must exist at this exact fully-qualified name in each
 * flavor source set. This copy provides nothing: in the mlNone build there is no LiteRT-LM and no
 * MediaPipe on the classpath, the AI feature reports itself unavailable, and the dependency graph
 * stays byte-identical to the pre-AI app — which is the whole point of the flavor split
 * (see docs/ai/PLAN.md D10).
 *
 * <p>The mlGemma twin lives at
 * {@code src/mlGemma/java/de/luhmer/owncloudnewsreader/di/AiModule.java}. Keep the two in sync:
 * every {@code @Provides} added there needs a counterpart here, or a no-op/absent binding that
 * {@code AppComponent} can still satisfy.
 */
@Module
public class AiModule {
}
