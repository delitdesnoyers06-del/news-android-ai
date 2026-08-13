package de.luhmer.owncloudnewsreader.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.AiResponseFormat;

/**
 * A scripted {@link AiLlm}. Because the engine interfaces live in {@code src/main} and carry no
 * LiteRT type, the entire scoring half — prompt assembly, the parser, the repair ladder, the
 * per-article commit and the terminal guarantee — runs here on a plain JVM.
 */
public class FakeLlm implements AiLlm {

    /** Decides what the model "says" for a given turn. */
    public interface Script {
        String reply(String userText, int turnIndex) throws AiException;
    }

    /** Every user turn the fake was given, in order. */
    public final List<String> prompts = new ArrayList<>();
    /**
     * The per-call {@link AiResponseFormat} of every turn, in step with {@link #prompts}. A null
     * entry means "the conversation's own format"; {@link AiResponseFormat#NONE} means the caller
     * explicitly dropped the grammar for that turn.
     */
    public final List<AiResponseFormat> formats = new ArrayList<>();
    /** The grammar of every {@link AiPromptSpec} the scorer opened a conversation with. */
    public final List<String> specGrammars = new ArrayList<>();
    public int conversationsOpened;
    public int conversationsClosed;

    private final Script script;
    private final boolean constrained;
    private final AiModelInfo info;
    private int turn;

    public FakeLlm(Script script) {
        this(script, true, 2_588_147_712L);
    }

    public FakeLlm(Script script, boolean constrained, long sizeBytes) {
        this.script = script;
        this.constrained = constrained;
        this.info = new AiModelInfo("fake", "Fake", sizeBytes, new File("/dev/null"), constrained,
                2048);
    }

    /** Always answers correctly for a batch of any size. */
    public static FakeLlm perfect() {
        return new FakeLlm((userText, t) -> {
            int n = countArticles(userText);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                sb.append(i).append("|").append(i % 4).append("|drt|why ").append(i).append('\n');
            }
            return sb.toString();
        });
    }

    /** Never produces a parseable line, on any turn. The never-lose-an-article stress case. */
    public static FakeLlm garbage() {
        return new FakeLlm((userText, t) ->
                "I'm sorry, I can't help with that.\n```\n<<<>>>\n```\n");
    }

    /** Throws on every turn — an OOM or a native crash surfaced as an exception. */
    public static FakeLlm exploding() {
        return new FakeLlm((userText, t) -> {
            throw new AiException(AiException.Kind.RUNTIME, "boom");
        });
    }

    static int countArticles(String userText) {
        int n = 0;
        for (String line : userText.split("\n")) {
            if (line.startsWith("[") && line.contains("]")) {
                n++;
            }
        }
        return n;
    }

    @Override
    public AiModelInfo model() {
        return info;
    }

    @Override
    public boolean supportsConstrainedDecoding() {
        return constrained;
    }

    @Override
    public AiConversation start(AiPromptSpec spec) {
        conversationsOpened++;
        specGrammars.add(spec == null ? null : spec.grammar);
        return new AiConversation() {
            @Override
            public String send(String userText) throws AiException {
                return send(userText, null);
            }

            @Override
            public String send(String userText, AiResponseFormat format) throws AiException {
                prompts.add(userText);
                formats.add(format);
                return script.reply(userText, turn++);
            }

            @Override
            public void cancel() {
                // no-op
            }

            @Override
            public void close() {
                conversationsClosed++;
            }
        };
    }

    @Override
    public void close() {
        // no native resources
    }
}
