package de.luhmer.owncloudnewsreader.ai.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalog;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.model.AiPartMeta;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiModelRegistry;

/**
 * What is on disk, and which model each stage should use.
 *
 * <h3>Storage layout</h3>
 * <pre>
 *   getExternalFilesDir(null)/ai-models/&lt;id&gt;/&lt;revision&gt;/&lt;fileName&gt;        final weights
 *                                                        /&lt;fileName&gt;.part   in-flight body
 *                                                        /&lt;fileName&gt;.meta   the sidecar
 *   getCacheDir()/litertlm/&lt;id&gt;/                                            EngineConfig.cacheDir
 * </pre>
 * Two deliberate choices. Weights live on the <b>external files dir</b>, a different root from
 * {@code NewsFileUtils.getCacheDirPath()} ({@code getExternalCacheDir()}), so the existing
 * "clear cache" handler can never delete 2.6 GB of downloaded model. The engine cache lives on
 * <b>internal</b> storage — a different volume from the thing it caches, so its growth can never
 * exhaust the volume holding the weights, and the OS may reclaim it at the cost of a slow next load
 * rather than a broken model.
 *
 * <h3>The one failure shape worth designing against</h3>
 * A preference pointing at a file that is gone is a silent, permanent AI outage (PLAN R8).
 * {@link #delete(String)} resets every pref that named the model, {@link #scan()} clears
 * {@code AI_MODEL.PATH} the moment a file disappears, and {@link #resolveLlm} verifies the file on
 * every call rather than trusting the registry.
 */
public class AiModelRepository {

    private static final String TAG = "AiModelRepository";

    public static final String MODELS_DIR = "ai-models";
    public static final String ENGINE_CACHE_DIR = "litertlm";

    private final Context app;
    private final AiCatalog catalog;
    private final AiDb db;

    public AiModelRepository(Context context) {
        this(context, dbOf(context));
    }

    public AiModelRepository(Context context, AiDb db) {
        this.app = context == null ? null : context.getApplicationContext();
        this.catalog = AiCatalog.of(this.app);
        this.db = db;
    }

    private static AiDb dbOf(Context context) {
        try {
            return new DatabaseConnectionOrm(context).aiDb();
        } catch (Throwable t) {
            Log.w(TAG, "AI database unavailable", t);
            return null;
        }
    }

    public AiCatalog catalog() {
        return catalog;
    }

    // ---- layout ----------------------------------------------------------------------------

    public File modelsRoot() {
        return new File(app.getExternalFilesDir(null), MODELS_DIR);
    }

    public File dirFor(AiCatalogEntry e) {
        String rev = e.revision == null || e.revision.isEmpty() ? "main" : e.revision;
        return new File(new File(modelsRoot(), e.id), rev);
    }

    public File fileFor(AiCatalogEntry e) {
        return new File(dirFor(e), e.fileName);
    }

    public File partFor(AiCatalogEntry e) {
        return new File(dirFor(e), e.fileName + ".part");
    }

    public File metaFor(AiCatalogEntry e) {
        return AiPartMeta.sidecarFor(partFor(e));
    }

    /** {@code EngineConfig.cacheDir} — internal, reclaimable, never the weights' volume. */
    public File engineCacheDir(String modelId) {
        File d = new File(new File(app.getCacheDir(), ENGINE_CACHE_DIR), modelId);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ---- state -----------------------------------------------------------------------------

    /** What the model manager renders. Mirrors {@code AI_MODEL.STATE} plus the on-disk truth. */
    public static final class Status {
        public final AiCatalogEntry entry;
        public final String state;
        public final long receivedBytes;
        public final String lastError;

        Status(AiCatalogEntry entry, String state, long receivedBytes, String lastError) {
            this.entry = entry;
            this.state = state;
            this.receivedBytes = receivedBytes;
            this.lastError = lastError;
        }

        public boolean installed() {
            return AiModelRegistry.STATE_INSTALLED.equals(state);
        }

        public boolean partial() {
            return AiModelRegistry.STATE_PARTIAL.equals(state);
        }

        public int percent() {
            if (entry.sizeBytes <= 0) {
                return 0;
            }
            return (int) Math.min(100L, receivedBytes * 100L / entry.sizeBytes);
        }
    }

    /**
     * Reconciles {@code AI_MODEL} with what is actually on disk, in both directions.
     *
     * <p>Disk wins. A model whose file vanished (an OS eviction, a user with a file manager, a
     * factory-reset SD card) is demoted to {@code absent} and its {@code PATH} cleared here, which is
     * the cheapest place to stop a dangling path before it reaches the engine.</p>
     */
    public List<Status> scan() {
        List<Status> out = new ArrayList<>();
        AiModelRegistry registry = db == null ? null : new AiModelRegistry(db);
        for (AiCatalogEntry e : catalog.all()) {
            if (registry != null) {
                registry.register(e.id, e.isLlm()
                        ? AiModelRegistry.KIND_LLM : AiModelRegistry.KIND_EMBEDDER,
                        e.sizeBytes, e.sha256);
            }
            File finalFile = fileFor(e);
            File part = partFor(e);
            String state;
            long received = 0L;
            String lastError = null;
            AiModelRegistry.Row row = registry == null ? null : registry.get(e.id);
            if (row != null) {
                lastError = row.lastError;
            }
            if (finalFile.isFile() && finalFile.length() == e.sizeBytes) {
                state = AiModelRegistry.STATE_INSTALLED;
                received = e.sizeBytes;
                if (registry != null) {
                    registry.setState(e.id, state, finalFile.getAbsolutePath(), null);
                    registry.setProgress(e.id, received, e.sizeBytes, null);
                }
            } else if (finalFile.isFile()) {
                // Right name, wrong length: the file on disk is not the file the catalogue names.
                state = AiModelRegistry.STATE_BROKEN;
                lastError = "size mismatch";
                if (registry != null) {
                    registry.setState(e.id, state, null, lastError);
                }
            } else if (part.isFile() && part.length() > 0) {
                state = AiModelRegistry.STATE_PARTIAL;
                received = part.length();
                if (registry != null) {
                    registry.setState(e.id, state, null, lastError);
                    registry.setProgress(e.id, received, e.sizeBytes, null);
                }
            } else if (row != null && AiModelRegistry.STATE_BROKEN.equals(row.state)) {
                state = AiModelRegistry.STATE_BROKEN;
            } else {
                state = AiModelRegistry.STATE_ABSENT;
                if (registry != null && row != null
                        && !AiModelRegistry.STATE_ABSENT.equals(row.state)) {
                    registry.setState(e.id, state, null, null);
                }
            }
            out.add(new Status(e, state, received, lastError));
        }
        return out;
    }

    public Status statusOf(String modelId) {
        for (Status s : scan()) {
            if (s.entry.id.equals(modelId)) {
                return s;
            }
        }
        return null;
    }

    /** True when the file is present at the catalogue's exact size. Does not consult the registry. */
    public boolean isInstalled(AiCatalogEntry e) {
        File f = fileFor(e);
        return f.isFile() && f.length() == e.sizeBytes;
    }

    public AiModelInfo infoFor(String modelId) {
        AiCatalogEntry e = catalog.byId(modelId);
        if (e == null || !isInstalled(e)) {
            return null;
        }
        boolean sp = e.sentencePiece && !probedNoConstrainedDecoding(modelId);
        return new AiModelInfo(e.id, e.displayName, e.sizeBytes, fileFor(e), sp,
                e.defaultMaxNumTokens);
    }

    /**
     * The on-device probe's verdict, when it ran. A catalogue flag is a claim about a file we have
     * not opened; {@code AI_META} holds what actually happened when we did.
     */
    private boolean probedNoConstrainedDecoding(String modelId) {
        if (db == null) {
            return false;
        }
        return "0".equals(db.getMeta(metaKeySentencePiece(modelId)));
    }

    public static String metaKeySentencePiece(String modelId) {
        return "model_sp:" + modelId;
    }

    public static String metaKeyLoadMs(String modelId) {
        return "model_load_ms:" + modelId;
    }

    // ---- resolution ------------------------------------------------------------------------

    /**
     * The fallback chain (doc §3.2). Every step is silent and logged; the user sees the outcome in
     * the status strip, never a crash.
     *
     * <ol>
     *   <li>{@code sp_ai_model_<stage>}: {@code __off__} disables the stage, {@code __same_as_triage__}
     *       restarts at the triage key, an id is used when it is installed and passes {@code selectOk}</li>
     *   <li>the tier default, if installed</li>
     *   <li>any installed LLM passing {@code selectOk}, largest first</li>
     *   <li>any installed LLM at all — the user pressed "Try anyway"</li>
     *   <li>none</li>
     * </ol>
     *
     * @return the model to use, or {@code null} when the stage must degrade
     */
    public AiModelInfo resolveLlm(SharedPreferences prefs, String stageKey) {
        String value = prefs == null ? null : prefs.getString(stageKey, null);
        if (SettingsActivity.AI_MODEL_OFF.equals(value)) {
            return null;
        }
        if (SettingsActivity.AI_MODEL_SAME_AS_TRIAGE.equals(value)
                && !SettingsActivity.SP_AI_MODEL_TRIAGE.equals(stageKey)) {
            return resolveLlm(prefs, SettingsActivity.SP_AI_MODEL_TRIAGE);
        }
        if (value != null && !value.isEmpty()
                && !SettingsActivity.AI_MODEL_SAME_AS_TRIAGE.equals(value)) {
            AiModelInfo chosen = usableLlm(catalog.byId(value), true);
            if (chosen != null) {
                return chosen;
            }
            Log.i(TAG, "preferred model " + value + " unusable; falling back");
        }
        AiModelInfo tierDefault =
                usableLlm(catalog.defaultLlmFor(AiCapability.tier(app)), true);
        if (tierDefault != null) {
            return tierDefault;
        }
        List<AiCatalogEntry> llms = new ArrayList<>(catalog.llms());
        Collections.sort(llms, LARGEST_FIRST);
        for (AiCatalogEntry e : llms) {
            AiModelInfo info = usableLlm(e, true);
            if (info != null) {
                return info;
            }
        }
        for (AiCatalogEntry e : llms) {
            AiModelInfo info = usableLlm(e, false);      // ignore selectOk: "Try anyway"
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    private AiModelInfo usableLlm(AiCatalogEntry e, boolean requireSelectOk) {
        if (e == null || !e.isLlm() || !isInstalled(e)) {
            return null;
        }
        if (requireSelectOk && !AiCapability.selectOk(app, e.minTotalRamBytes)) {
            return null;
        }
        return infoFor(e.id);
    }

    private static final Comparator<AiCatalogEntry> LARGEST_FIRST =
            (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);

    // ---- deletion --------------------------------------------------------------------------

    /**
     * Removes the weights, the partial download, the sidecar and the engine cache, then resets every
     * preference that named the model back to its sentinel. Never leaves a dangling path.
     *
     * @return bytes freed
     */
    public long delete(String modelId) {
        AiCatalogEntry e = catalog.byId(modelId);
        if (e == null) {
            return 0L;
        }
        long freed = deleteRecursively(new File(modelsRoot(), e.id));
        freed += deleteRecursively(new File(new File(app.getCacheDir(), ENGINE_CACHE_DIR), e.id));
        if (db != null) {
            new AiModelRegistry(db).setState(e.id, AiModelRegistry.STATE_ABSENT, null, null);
            new AiModelRegistry(db).setProgress(e.id, 0L, e.sizeBytes, null);
            db.deleteMeta(metaKeySentencePiece(e.id));
            db.deleteMeta(metaKeyLoadMs(e.id));
        }
        resetPrefsNaming(modelId);
        return freed;
    }

    /** @return the pref keys that were reset, so the caller can toast about exactly those */
    public List<String> resetPrefsNaming(String modelId) {
        List<String> reset = new ArrayList<>();
        SharedPreferences prefs = AiFeature.prefsOf(app);
        SharedPreferences.Editor ed = prefs.edit();
        String[] keys = {
                SettingsActivity.SP_AI_MODEL_TRIAGE, SettingsActivity.SP_AI_MODEL_DIGEST,
                SettingsActivity.SP_AI_MODEL_ENRICH, SettingsActivity.SP_AI_MODEL_LEARN,
        };
        for (String key : keys) {
            if (modelId.equals(prefs.getString(key, null))) {
                ed.putString(key, SettingsActivity.SP_AI_MODEL_TRIAGE.equals(key)
                        ? "" : SettingsActivity.AI_MODEL_SAME_AS_TRIAGE);
                reset.add(key);
            }
        }
        ed.apply();
        return reset;
    }

    private static long deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return 0L;
        }
        long freed = 0L;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                freed += deleteRecursively(kid);
            }
        }
        if (f.isFile()) {
            freed += f.length();
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return freed;
    }

    // ---- summaries -------------------------------------------------------------------------

    public long installedBytes() {
        long total = 0L;
        for (AiCatalogEntry e : catalog.all()) {
            if (isInstalled(e)) {
                total += e.sizeBytes;
            }
        }
        return total;
    }

    public int installedCount() {
        int n = 0;
        for (AiCatalogEntry e : catalog.all()) {
            if (isInstalled(e)) {
                n++;
            }
        }
        return n;
    }

    public boolean hasAnyInstalledLlm() {
        for (AiCatalogEntry e : catalog.llms()) {
            if (isInstalled(e)) {
                return true;
            }
        }
        return false;
    }

    public boolean embedderInstalled() {
        AiCatalogEntry e = catalog.embedder();
        return e != null && isInstalled(e);
    }

    public long freeExternalBytes() {
        return AiCapability.freeBytes(app.getExternalFilesDir(null));
    }

    /** Static convenience for the settings screen, which has no repository instance. */
    public static boolean hasAnyInstalledLlm(Context context) {
        try {
            return new AiModelRepository(context).hasAnyInstalledLlm();
        } catch (Throwable t) {
            Log.w(TAG, "install check failed", t);
            return false;
        }
    }
}
