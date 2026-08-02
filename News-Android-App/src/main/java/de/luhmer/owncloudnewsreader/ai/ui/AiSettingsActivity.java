package de.luhmer.owncloudnewsreader.ai.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.helper.ThemeChooser;

/**
 * Host for {@link AiSettingsFragment}.
 *
 * <p>A separate Activity rather than a nested {@code PreferenceScreen} with
 * {@code OnPreferenceStartFragmentCallback}: the model manager is a RecyclerView with determinate
 * progress bars and per-row buttons, which is not expressible as a preference list, so a second
 * Activity is needed regardless. Going this way leaves {@code SettingsActivity} and
 * {@code SettingsFragment} untouched apart from two lines and one bind method.</p>
 *
 * <p>{@code activity_settings.xml} is reused verbatim — toolbar include plus a {@code @id/container}
 * FrameLayout.</p>
 */
public class AiSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pref_header_ai);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, new AiSettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
