package de.luhmer.owncloudnewsreader.ai.prompt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.RawRes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@code res/raw} prompt with {@code {{name}}} placeholders, substituted in a <b>single pass</b>.
 *
 * <h3>Why single-pass and not chained {@code String.replace}</h3>
 * {@code {{articles}}} is scraped text from arbitrary news sites. With chained replaces, an article
 * whose title contains the literal {@code {{interests}}} would have the reader's interests note
 * spliced into it by the <i>next</i> replace in the chain — a prompt-injection primitive handed over
 * for free. Scanning the template once and copying values in verbatim means a value is never itself
 * scanned for placeholders. The parser is the guarantee; this is the same idea one layer up.
 *
 * <p>Prompts live in {@code res/raw} rather than {@code strings.xml} deliberately: no XML escaping of
 * {@code <}, {@code &}, {@code "}; no {@code %} doubling; no {@code StringFormatInvalid} lint; and
 * they stay out of the translation pipeline, since they are English-authored regardless of the
 * device locale (the model is asked to <i>answer</i> in the locale, which is a different thing).
 */
public final class PromptTemplate {

    private static final String TAG = "PromptTemplate";

    private static final Map<Integer, PromptTemplate> CACHE = new ConcurrentHashMap<>();

    private final String body;

    private PromptTemplate(String body) {
        this.body = body == null ? "" : body;
    }

    /** Test seam: build a template from a literal, with the same substitution semantics. */
    public static PromptTemplate of(String body) {
        return new PromptTemplate(body);
    }

    /** Loads and caches a {@code res/raw} prompt. Never throws; a missing file yields "". */
    public static PromptTemplate load(Context context, @RawRes int resId) {
        PromptTemplate hit = CACHE.get(resId);
        if (hit != null) {
            return hit;
        }
        String text = "";
        try (InputStream in = context.getResources().openRawResource(resId)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            text = new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "prompt " + resId + " unreadable", t);
        }
        PromptTemplate tpl = new PromptTemplate(text);
        CACHE.put(resId, tpl);
        return tpl;
    }

    public String raw() {
        return body;
    }

    public String render() {
        return render(Collections.<String, String>emptyMap());
    }

    /**
     * Substitutes every {@code {{name}}} for which {@code vars} has an entry.
     *
     * <p>An <b>unknown</b> placeholder is left in place rather than blanked: a prompt that visibly
     * says {@code {{interests}}} is a bug you find in one glance, whereas a silently empty INTERESTS
     * section is a model that quietly rates everything against nothing.</p>
     */
    public String render(Map<String, String> vars) {
        Map<String, String> v = vars == null ? Collections.<String, String>emptyMap() : vars;
        StringBuilder out = new StringBuilder(body.length() + 256);
        int i = 0;
        final int n = body.length();
        while (i < n) {
            int open = body.indexOf("{{", i);
            if (open < 0) {
                out.append(body, i, n);
                break;
            }
            int close = body.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(body, i, n);
                break;
            }
            out.append(body, i, open);
            String name = body.substring(open + 2, close).trim();
            if (v.containsKey(name)) {
                String value = v.get(name);
                // Appended verbatim and never re-scanned: this is the single-pass guarantee.
                out.append(value == null ? "" : value);
            } else {
                out.append(body, open, close + 2);
            }
            i = close + 2;
        }
        return out.toString();
    }

    /** Convenience for the common two/four-variable calls. */
    public static Map<String, String> vars(String... keyThenValue) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < keyThenValue.length; i += 2) {
            m.put(keyThenValue[i], keyThenValue[i + 1]);
        }
        return m;
    }

    /** Test/diagnostic helper: the placeholders a render would have left behind. */
    public static boolean hasUnsubstituted(String rendered) {
        int open = rendered.indexOf("{{");
        return open >= 0 && rendered.indexOf("}}", open + 2) > open;
    }
}
