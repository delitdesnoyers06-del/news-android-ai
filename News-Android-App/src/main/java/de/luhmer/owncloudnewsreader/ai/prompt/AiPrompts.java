package de.luhmer.owncloudnewsreader.ai.prompt;

import android.content.Context;

import de.luhmer.owncloudnewsreader.R;

/**
 * The three scoring prompts, loaded once. Exists so {@code AiScorer} takes prompts as data and can
 * therefore be driven from a plain JUnit test with literals instead of a {@code Context}.
 */
public final class AiPrompts {

    public final PromptTemplate system;
    public final PromptTemplate user;
    public final PromptTemplate repair;

    private AiPrompts(PromptTemplate system, PromptTemplate user, PromptTemplate repair) {
        this.system = system;
        this.user = user;
        this.repair = repair;
    }

    public static AiPrompts of(Context context) {
        return new AiPrompts(
                PromptTemplate.load(context, R.raw.prompt_score_system),
                PromptTemplate.load(context, R.raw.prompt_score_user),
                PromptTemplate.load(context, R.raw.prompt_score_repair));
    }

    /**
     * The digest-abstract pair. Loaded separately from the scoring trio because the digest runs at a
     * different time, from a different worker, and may run with no scoring model configured at all.
     */
    public static PromptTemplate abstractSystem(Context context) {
        return PromptTemplate.load(context, R.raw.prompt_abstract_system);
    }

    public static PromptTemplate abstractUser(Context context) {
        return PromptTemplate.load(context, R.raw.prompt_abstract_user);
    }

    public static PromptTemplate tasteSystem(Context context) {
        return PromptTemplate.load(context, R.raw.prompt_taste_system);
    }

    public static PromptTemplate tasteUser(Context context) {
        return PromptTemplate.load(context, R.raw.prompt_taste_user);
    }

    /** Test seam. */
    public static AiPrompts of(String system, String user, String repair) {
        return new AiPrompts(PromptTemplate.of(system), PromptTemplate.of(user),
                PromptTemplate.of(repair));
    }
}
