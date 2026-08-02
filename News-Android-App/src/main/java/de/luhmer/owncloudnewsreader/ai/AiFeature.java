package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.content.SharedPreferences;

import de.luhmer.owncloudnewsreader.SettingsActivity;

/**
 * The single {@code isEnabled()} gate for the whole AI half (PLAN §4.4).
 *
 * <p>Two conditions, both necessary: the device can run it ({@link AiCapability#isSupported}) and
 * the user has turned it on ({@code cb_ai_enabled}, default <b>off</b>). Everything user-visible —
 * the drawer row, the fast-action buttons, the swipe override, the worker — asks this class and
 * nothing else, so there is exactly one place to change when the gating regime moves.</p>
 *
 * <p>No AI runtime type is referenced, so this compiles and answers {@code false} identically in
 * the {@code mlNone} flavor once the preference is absent.</p>
 */
public final class AiFeature {

    private AiFeature() {
        // no instances
    }

    /** Default is OFF. A feature that loads a multi-GB model never opts a user in for them. */
    public static final boolean DEFAULT_ENABLED = false;

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return isEnabled(context, prefsOf(context));
    }

    /**
     * The app does not use {@code PreferenceManager.getDefaultSharedPreferences} — {@code ApiModule}
     * provides {@code getSharedPreferences(packageName + "_preferences")} explicitly. Reading a
     * different file here would make the switch appear off to half the code.
     */
    public static SharedPreferences prefsOf(Context context) {
        Context app = context.getApplicationContext();
        return app.getSharedPreferences(app.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) {
            return false;
        }
        if (!AiCapability.isSupported(context)) {
            return false;
        }
        return prefs.getBoolean(SettingsActivity.CB_AI_ENABLED, DEFAULT_ENABLED);
    }

    /**
     * The master switch alone, ignoring the device gate. Used by the settings screen itself, which
     * must still render the switch it owns.
     */
    public static boolean switchOn(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(SettingsActivity.CB_AI_ENABLED, DEFAULT_ENABLED);
    }
}
