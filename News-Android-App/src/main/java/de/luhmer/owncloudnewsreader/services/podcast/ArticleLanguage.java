package de.luhmer.owncloudnewsreader.services.podcast;

import android.content.Context;
import android.os.Build;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import androidx.annotation.RequiresApi;

/**
 * Best-effort detection of an article's language so the right neural voice can be chosen.
 *
 * <p>Uses the on-device {@link TextClassifier} (API 29+), which needs no extra dependency and sends
 * nothing off the device — in keeping with this app's privacy stance. Below API 29, or when the
 * classifier is unavailable, it returns {@code null} and the caller falls back to the user's default
 * voice.</p>
 */
public final class ArticleLanguage {

    private static final int SAMPLE_CHARS = 600;

    private ArticleLanguage() { }

    /** The BCP-47 base language (e.g. {@code "en"}, {@code "fr"}) of {@code text}, or {@code null}. */
    public static String detect(Context context, String text) {
        String sample = sample(text);
        if (context == null || sample.isEmpty()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return viaTextClassifier(context, sample);
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static String viaTextClassifier(Context context, String sample) {
        try {
            TextClassificationManager tcm = (TextClassificationManager)
                    context.getSystemService(Context.TEXT_CLASSIFICATION_SERVICE);
            if (tcm == null) {
                return null;
            }
            TextClassifier tc = tcm.getTextClassifier();
            TextLanguage tl = tc.detectLanguage(
                    new TextLanguage.Request.Builder(sample).build());
            if (tl.getLocaleHypothesisCount() > 0) {
                String lang = tl.getLocale(0).getLanguage();
                return (lang == null || lang.isEmpty()) ? null : lang;
            }
        } catch (Throwable t) {
            // detection is best-effort; any failure means "unknown"
        }
        return null;
    }

    /** Tags stripped, whitespace collapsed, capped: enough signal without scanning a whole article. */
    private static String sample(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return s.length() > SAMPLE_CHARS ? s.substring(0, SAMPLE_CHARS) : s;
    }
}
