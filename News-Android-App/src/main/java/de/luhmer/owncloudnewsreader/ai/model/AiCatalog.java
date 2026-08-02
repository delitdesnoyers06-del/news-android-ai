package de.luhmer.owncloudnewsreader.ai.model;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.ai.AiCapability;

/**
 * The hardcoded model catalogue, parsed once from {@code res/raw/ai_model_catalog.json}.
 *
 * <p>Hardcoded on purpose: a remote catalogue would be a network call this app makes on behalf of
 * nobody, in a build whose {@code PRIVACY.md} promises the opposite. The cost is that adding a model
 * needs an app update — accepted.</p>
 *
 * <p>Parsing never throws. A malformed catalogue yields an <b>empty</b> catalogue, which the model
 * manager renders as "no models available" — not a crash on the settings screen.</p>
 */
public final class AiCatalog {

    private static final String TAG = "AiCatalog";

    /** Tier defaults, PLAN §4.5. */
    public static final String DEFAULT_LLM_FULL_TIER = "gemma-4-E2B";
    public static final String DEFAULT_LLM_LIGHT_TIER = "qwen3-0.6b-int4";
    /** Must equal {@code AiEngineManager.EMBEDDING_MODEL_ID}; asserted by {@code AiCatalogTest}. */
    public static final String EMBEDDER_ID = "embedding_gemma_int4int8";

    private static volatile AiCatalog cached;

    private final List<AiCatalogEntry> entries;

    private AiCatalog(List<AiCatalogEntry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /** Gson DTO for the file's top level. */
    private static final class Doc {
        int version;
        List<AiCatalogEntry> entries;
    }

    public static AiCatalog of(Context context) {
        AiCatalog local = cached;
        if (local != null) {
            return local;
        }
        synchronized (AiCatalog.class) {
            if (cached == null) {
                cached = parse(read(context));
            }
            return cached;
        }
    }

    /** Test seam: parse an arbitrary JSON string with the same guarantees. */
    public static AiCatalog parse(String json) {
        List<AiCatalogEntry> out = new ArrayList<>();
        try {
            Doc doc = new Gson().fromJson(json, Doc.class);
            if (doc != null && doc.entries != null) {
                for (AiCatalogEntry e : doc.entries) {
                    if (e != null && e.id != null && !e.id.isEmpty() && e.sizeBytes > 0) {
                        out.add(e);
                    }
                }
            }
        } catch (Throwable t) {
            // A broken catalogue must not take the settings screen down with it.
            Log.e(TAG, "catalogue unreadable; continuing with none", t);
            out.clear();
        }
        return new AiCatalog(out);
    }

    private static String read(Context context) {
        if (context == null) {
            return "";
        }
        try (InputStream in = context.getResources().openRawResource(R.raw.ai_model_catalog)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not read ai_model_catalog.json", e);
            return "";
        }
    }

    public List<AiCatalogEntry> all() {
        return entries;
    }

    public AiCatalogEntry byId(String id) {
        if (id == null) {
            return null;
        }
        for (AiCatalogEntry e : entries) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    public List<AiCatalogEntry> llms() {
        List<AiCatalogEntry> out = new ArrayList<>();
        for (AiCatalogEntry e : entries) {
            if (e.isLlm()) {
                out.add(e);
            }
        }
        return out;
    }

    public AiCatalogEntry embedder() {
        return byId(EMBEDDER_ID);
    }

    /**
     * The scoring model this device should get by default. {@code null} on an unsupported device —
     * there is no "least bad" model for hardware that cannot load the runtime at all.
     */
    public AiCatalogEntry defaultLlmFor(AiCapability.Tier tier) {
        if (tier == AiCapability.Tier.FULL) {
            return byId(DEFAULT_LLM_FULL_TIER);
        }
        if (tier == AiCapability.Tier.LIGHT) {
            return byId(DEFAULT_LLM_LIGHT_TIER);
        }
        return null;
    }
}
