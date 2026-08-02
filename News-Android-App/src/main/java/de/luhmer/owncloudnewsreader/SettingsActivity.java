/*
* Android ownCloud News
*
* @author David Luhmer
* @copyright 2013 David Luhmer david-dev@live.de
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
* License as published by the Free Software Foundation; either
* version 3 of the License, or any later version.
*
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU AFFERO GENERAL PUBLIC LICENSE for more details.
*
* You should have received a copy of the GNU Affero General Public
* License along with this library.  If not, see <http://www.gnu.org/licenses/>.
*
*/

package de.luhmer.owncloudnewsreader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import javax.inject.Inject;

import de.luhmer.owncloudnewsreader.helper.ThemeChooser;

/**
* A {@link PreferenceActivity} that presents a set of application settings. On
* handset devices, settings are presented as a single list. On tablets,
* settings are split by category, with category headers shown to the left of
* the list of settings.
* <p>
* See <a href="http://developer.android.com/design/patterns/settings.html">
* Android Design: Settings</a> for design guidelines and the <a
* href="http://developer.android.com/guide/topics/ui/settings.html">Settings
* API Guide</a> for more information on developing a Settings UI.
*/
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = SettingsActivity.class.getCanonicalName();

    /**
     * Determines whether to always show the simplified settings UI, where
     * settings are presented in a single list. When false, settings are shown
     * as a master/detail two-pane view on tablets. When true, a single pane is
     * shown on tablets.
     */
    public static final String EDT_USERNAME_STRING = "edt_username";
    public static final String EDT_PASSWORD_STRING = "edt_password";
    public static final String EDT_OWNCLOUDROOTPATH_STRING = "edt_owncloudRootPath";
    public static final String SW_USE_SINGLE_SIGN_ON = "sw_use_single_sign_on";
    public static final String EDT_CLEAR_CACHE = "edt_clearCache";

    //public static final String CB_ALLOWALLSSLCERTIFICATES_STRING = "cb_AllowAllSSLCertificates";
    public static final String CB_SYNCONSTARTUP_STRING = "cb_AutoSyncOnStart";
    public static final String CB_SHOWONLYUNREAD_STRING = "cb_ShowOnlyUnread";
    public static final String CB_NAVIGATE_WITH_VOLUME_BUTTONS_STRING = "cb_NavigateWithVolumeButtons";

    public static final String LV_CACHE_IMAGES_OFFLINE_STRING = "lv_cacheImagesOffline";

    public static final String CB_MARK_AS_READ_WHILE_SCROLLING_STRING = "cb_MarkAsReadWhileScrolling";
    public static final String CB_SYNC_WHEN_SCROLLED_TO_BOTTOM_STRING = "cb_SyncWhenScrolledToBottom";
    public static final String CB_SHOW_FAST_ACTIONS = "cb_ShowFastActions";
    public static final String CB_PREF_BACK_OPENS_DRAWER = "cb_prefBackButtonOpensDrawer";
    public static final String CB_DISABLE_HOSTNAME_VERIFICATION_STRING = "cb_DisableHostnameVerification";
    public static final String CB_SKIP_DETAILVIEW_AND_OPEN_BROWSER_DIRECTLY_STRING = "cb_openInBrowserDirectly";

    //public static final String CB_ENABLE_PODCASTS_STRING = "cb_enablePodcasts";

    public static final String PREF_SERVER_SETTINGS = "pref_server_settings";
    public static final String PREF_SYNC_SETTINGS = "pref_sync_settings";
    public static final String PREF_TTS_SETTINGS = "pref_tts_settings";
    public static final String SYNC_INTERVAL_IN_MINUTES_STRING_DEPRECATED = "SYNC_INTERVAL_IN_MINUTES_STRING";

    public static final String SP_APP_THEME = "sp_app_theme";
    public static final String CB_OLED_MODE = "cb_oled_mode";
    public static final String CB_DETAILED_VIEW_ZOOM = "cb_detailed_view_zoom";

    public static final String CB_EXTERNAL_PLAYER = "cb_external_player";

    public static final String SP_FEED_LIST_LAYOUT = "sp_feed_list_layout"; // used for shared prefs
    public static final String RI_FEED_LIST_LAYOUT = "ai_feed_list_layout"; // used for result intents
    public static final String SP_FONT_SIZE = "sp_font_size";

    public static final String RI_CACHE_CLEARED = "CACHE_CLEARED"; // used for result intents
    public static final String SP_MAX_CACHE_SIZE = "sp_max_cache_size";
    public static final String SP_SORT_ORDER = "sp_sort_order";
    public static final String SP_DISPLAY_BROWSER = "sp_display_browser";
    public static final String SP_SEARCH_IN = "sp_search_in";
    public static final String SP_SWIPE_RIGHT_ACTION = "sp_swipe_right_action";
    public static final String SP_SWIPE_LEFT_ACTION = "sp_swipe_left_action";
    public static final String SP_SWIPE_RIGHT_ACTION_DEFAULT = "1";
    public static final String SP_SWIPE_LEFT_ACTION_DEFAULT = "2";

    public static final String CB_VERSION = "cb_version";
    public static final String CB_REPORT_ISSUE = "cb_reportIssue";

    // ---- AI triage ----
    // The full key set is declared here (dev:ui-and-integration §5.1) so that the phases that own
    // the preference screens, the model manager and the worker policies all agree on the strings.
    // Only the ones the shipped code reads today are referenced; the rest are placeholders on
    // purpose - a key that changes spelling between phases silently orphans a user's setting.
    public static final String CB_AI_ENABLED = "cb_ai_enabled";
    public static final String PREF_AI_SETTINGS = "pref_ai_settings";

    public static final String SP_AI_MODEL_TRIAGE = "sp_ai_model_triage";
    public static final String SP_AI_MODEL_EMBEDDING = "sp_ai_model_embedding";
    public static final String SP_AI_MODEL_DIGEST = "sp_ai_model_digest";
    public static final String SP_AI_MODEL_ENRICH = "sp_ai_model_enrich";
    public static final String SP_AI_MODEL_LEARN = "sp_ai_model_learn";
    // D21: the digest/enrich/learn pickers collapse to two switches; the keys above stay so a
    // future advanced screen can set them and the resolver never changes.
    public static final String CB_AI_ENRICH_ENABLED     = "cb_ai_enrich_enabled";
    public static final String CB_AI_LEARN_ENABLED      = "cb_ai_learn_enabled";
    public static final String AI_MODEL_SAME_AS_TRIAGE = "__same_as_triage__";
    public static final String AI_MODEL_OFF = "__off__";

    public static final String PREF_AI_MANAGE_MODELS = "pref_ai_manage_models";
    public static final String PREF_AI_DELETE_MODELS = "pref_ai_delete_models";
    public static final String EDT_AI_HF_TOKEN = "edt_ai_hf_token";

    public static final String SP_AI_RUN_TRIGGER = "sp_ai_run_trigger";
    public static final String SP_AI_BATCH_BUDGET = "sp_ai_batch_budget";
    public static final String CB_AI_ANALYZE_ALL_UNREAD_WHILE_CHARGING =
            "cb_ai_analyze_all_unread_while_charging";
    public static final String SP_AI_SCORE_BATCH        = "sp_ai_score_batch";
    public static final String SP_AI_MIN_BATTERY = "sp_ai_min_battery";
    public static final String CB_AI_GPU_BACKEND = "cb_ai_gpu_backend";
    public static final String CB_AI_DOWNLOADS_WIFI_ONLY = "cb_ai_downloads_wifi_only";
    public static final String CB_AI_THERMAL_PAUSE = "cb_ai_thermal_pause";

    public static final String EDT_AI_INTERESTS = "edt_ai_interests";
    public static final String PREF_AI_SUGGEST_INTERESTS = "pref_ai_suggest_interests";
    public static final String CB_AI_STAR_IS_LIKE = "cb_ai_star_is_like";
    public static final String PREF_AI_RESET_TASTE = "pref_ai_reset_taste";
    /** Opens the append-only version list of the interests note (PLAN Phase 8, revert path). */
    public static final String PREF_AI_NOTE_HISTORY = "pref_ai_note_history";

    public static final String PREF_AI_LAST_RUN = "pref_ai_last_run";
    public static final String CB_AI_DEBUG_LOG = "cb_ai_debug_log";
    public static final String PREF_AI_DIGEST_NOTIFY = "cb_ai_digest_notify";
    public static final String SP_AI_DIGEST_NOTIFY_TIME = "sp_ai_digest_notify_time";

    protected @Inject SharedPreferences mPrefs;

    public Intent resultIntent = new Intent();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((NewsReaderApplication) getApplication()).getAppComponent().injectActivity(this);

        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_settings);

        setupActionBar();

        // some settings might add a few flags to the result Intent at runtime
        // (e.g. clearing cache / switching list layout / theme / ...)
        setResult(RESULT_OK, resultIntent);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container, new SettingsFragment())
                .commit();
    }

    private void setupActionBar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.title_activity_settings);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Fix GHSL-2021-1033
        String feedListLayout = mPrefs.getString(SettingsActivity.SP_FEED_LIST_LAYOUT, "0");
        resultIntent.putExtra(SettingsActivity.RI_FEED_LIST_LAYOUT, feedListLayout);
    }
}
