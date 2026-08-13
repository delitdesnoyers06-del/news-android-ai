package de.luhmer.owncloudnewsreader.ai.engine;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.engine.impl.AiEngines;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiModelRegistry;

/**
 * The lifecycle owner: nothing else opens or closes a model.
 *
 * <p><b>At most one engine resident, process-wide.</b> A single static {@link ReentrantLock}
 * serialises {@link #withEmbedder} and {@link #withLlm}, and the two evict each other — the strict
 * sequence is <i>embed all &rarr; close &rarr; prefilter (pure SQL and arithmetic) &rarr; open the
 * LLM &rarr; score &rarr; release</i>. The native side serialises inference anyway; the gate exists
 * so a second {@code initialize()} can never allocate while the first is still resident, which on a
 * 4 GB device is the difference between a slow sync and an {@code OutOfMemoryError} in a background
 * process.
 *
 * <p><b>Never a Dagger {@code @Provides Engine}.</b> A {@code @Singleton} has no lifecycle, so
 * nothing would ever {@code close()} multi-GB of mmapped weights — and
 * {@code NewsReaderApplication.onCreate} runs in <i>every</i> process this app declares, so a
 * provider would give one engine per process.
 *
 * <p><b>The flavor seam.</b> This class is in {@code src/main} and therefore must compile in
 * {@code mlNone}, where neither runtime is on the classpath. It never names a runtime type; it calls
 * {@code impl.AiEngines}, which exists <b>twice</b> at the same fully-qualified name — once per
 * {@code ml} source set, exactly like {@code AiModule}. The {@code mlNone} copy answers "not
 * installed" and every caller degrades.
 *
 * <p>The embedder is opened and closed per call: it is ~184 MB and a triage pass uses it once, in a
 * burst, before the LLM half needs the memory. The 90 s keep-alive belongs to the LLM, so that
 * triage &rarr; digest in the same run costs one {@code initialize()} rather than two.
 */
public class AiEngineManager {

    private static final String TAG = "AiEngineManager";

    /** The catalogue id of the embedding model. Persisted in {@code AI_EMBEDDING.MODEL}. */
    public static final String EMBEDDING_MODEL_ID = "embedding_gemma_int4int8";

    /** How long a loaded LLM survives after the last {@link #withLlm}. */
    public static final long IDLE_KEEPALIVE_MS = 90_000L;

    /**
     * The load watchdog (PLAN D25). 180 s, not 45: the first load of a 2.588 GB model writes the
     * rearranged-weight cache from scratch, which is plausibly 60-150 s on a mid-range phone. A
     * 45 s watchdog marks a perfectly good model BROKEN after a 2.6 GB download.
     */
    public static final long LOAD_TIMEOUT_MS = 180_000L;

    /** One gate for every engine in the process. Static: there may be several manager instances. */
    private static final ReentrantLock GATE = new ReentrantLock();

    private static final ExecutorService LOADER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-engine-loader");
        t.setDaemon(true);
        return t;
    });

    private static final ScheduledExecutorService KEEPALIVE =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-engine-keepalive");
                t.setDaemon(true);
                return t;
            });

    /** Guarded by {@link #GATE}. */
    private static AiLlm warmLlm;
    private static String warmModelId;
    private static ScheduledFuture<?> pendingClose;
    /** Written under the gate, read from any thread by {@link #shutdownNow}. */
    private static volatile CancelToken activeToken;
    private static final AtomicBoolean TRIM_HANDLER_INSTALLED = new AtomicBoolean(false);

    /** Unit of work run against a loaded engine. */
    public interface Work<T, R> {
        R run(T engine) throws AiException;
    }

    private final Context app;
    private final AiDb db;

    public AiEngineManager(Context context, AiDb db) {
        this.app = context == null ? null : context.getApplicationContext();
        this.db = db;
    }

    /**
     * Test seam: a manager that hands out {@code embedder} and performs no device or model checks.
     * The gate is still taken, so the "one engine at a time" invariant is exercised too.
     *
     * @param embedder the embedder to hand out; {@code null} means "not installed"
     */
    public static AiEngineManager forTesting(final AiEmbedder embedder) {
        return forTesting(embedder, null);
    }

    /** Test seam covering both halves. Either may be {@code null}, meaning "not installed". */
    public static AiEngineManager forTesting(final AiEmbedder embedder, final AiLlm llm) {
        return new AiEngineManager(null, null) {
            @Override
            public <R> R withEmbedder(Work<AiEmbedder, R> work) throws AiException {
                if (embedder == null) {
                    throw new AiException(AiException.Kind.NOT_INSTALLED, "no fake embedder");
                }
                GATE.lock();
                try {
                    return work.run(embedder);
                } finally {
                    GATE.unlock();
                }
            }

            @Override
            public <R> R withLlm(AiModelInfo ignored, boolean gpu, CancelToken token,
                                 Work<AiLlm, R> work) throws AiException {
                if (llm == null) {
                    throw new AiException(AiException.Kind.NOT_INSTALLED, "no fake llm");
                }
                GATE.lock();
                activeToken = token;
                try {
                    return work.run(llm);
                } finally {
                    activeToken = null;
                    GATE.unlock();
                }
            }

            @Override
            public boolean embedderAvailable() {
                return embedder != null;
            }
        };
    }

    // ---- embedder ---------------------------------------------------------------------------

    /**
     * Loads the embedder, runs {@code work}, closes it.
     *
     * @throws AiException {@code UNSUPPORTED_DEVICE} when the ABI/RAM gate says no,
     *                     {@code NOT_INSTALLED} when the model file is absent or unreadable,
     *                     {@code LOAD_FAILED} when the runtime refused it. Every one of those has a
     *                     defined fallback in the pipeline: no vectors this run, similarity stays
     *                     NULL, the prefilter runs its cold branch.
     */
    public <R> R withEmbedder(Work<AiEmbedder, R> work) throws AiException {
        assertNotMainThread();
        if (!AiCapability.isSupported(app)) {
            throw new AiException(AiException.Kind.UNSUPPORTED_DEVICE,
                    "device tier " + AiCapability.tier(app));
        }
        File model = embeddingModelFile();
        GATE.lock();
        try {
            // The embedder and the LLM must never be resident together: 184 MB plus 2.6 GB is the
            // allocation that gets a background process killed.
            cancelPendingCloseLocked();
            closeWarmLlmLocked("embedder needs the memory");
            try (AiEmbedder embedder = AiEngines.openEmbedder(app, model)) {
                return work.run(embedder);
            }
        } catch (AiException e) {
            throw e;
        } catch (OutOfMemoryError e) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "embedder OOM", e);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "embedder failed", t);
        } finally {
            GATE.unlock();
        }
    }

    /** True when a model file is installed and this device can load it. Cheap, no I/O beyond stat. */
    public boolean embedderAvailable() {
        if (!AiCapability.isSupported(app) || !AiEngines.embedderSupported()) {
            return false;
        }
        try {
            embeddingModelFile();
            return true;
        } catch (AiException e) {
            return false;
        }
    }

    private File embeddingModelFile() throws AiException {
        if (db == null) {
            throw new AiException(AiException.Kind.NOT_INSTALLED, "no AI database");
        }
        String path = new AiModelRegistry(db).pathOf(EMBEDDING_MODEL_ID);
        if (path == null) {
            throw new AiException(AiException.Kind.NOT_INSTALLED,
                    EMBEDDING_MODEL_ID + " is not installed");
        }
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new AiException(AiException.Kind.NOT_INSTALLED,
                    EMBEDDING_MODEL_ID + " missing at " + path);
        }
        return f;
    }

    // ---- llm --------------------------------------------------------------------------------

    /**
     * Loads (or reuses a warm) LLM, runs {@code work}, then leaves the engine resident for
     * {@link #IDLE_KEEPALIVE_MS} in case the next stage wants it.
     *
     * @param info  the model to run; obtained from {@code AiModelRepository.resolveLlm}
     * @param gpu   {@code cb_ai_gpu_backend}. Defaults to false on every tier — we have zero LiteRT
     *              GPU data and a GPU-delegate OOM presents as "the AI folder is permanently empty"
     *              on exactly the flagships this targets.
     * @param token cancellation, routed to {@link #shutdownNow(String)} from every source
     */
    public <R> R withLlm(AiModelInfo info, boolean gpu, CancelToken token, Work<AiLlm, R> work)
            throws AiException {
        assertNotMainThread();
        if (!AiCapability.isSupported(app)) {
            throw new AiException(AiException.Kind.UNSUPPORTED_DEVICE,
                    "device tier " + AiCapability.tier(app));
        }
        if (!AiEngines.llmSupported()) {
            throw new AiException(AiException.Kind.NOT_INSTALLED,
                    "this build has no on-device AI runtime");
        }
        if (info == null || info.file == null || !info.file.isFile()) {
            throw new AiException(AiException.Kind.NOT_INSTALLED, "no scoring model on disk");
        }
        installTrimHandler();
        GATE.lock();
        activeToken = token;
        try {
            cancelPendingCloseLocked();
            if (warmLlm != null && !info.catalogId.equals(warmModelId)) {
                closeWarmLlmLocked("a different model was requested");
            }
            if (warmLlm == null) {
                warmLlm = openWithWatchdog(info, gpu);
                warmModelId = info.catalogId;
            }
            return work.run(warmLlm);
        } catch (AiException e) {
            if (e.kind == AiException.Kind.OUT_OF_MEMORY || e.kind == AiException.Kind.LOAD_FAILED) {
                closeWarmLlmLocked(e.kind.name());
            }
            throw e;
        } catch (OutOfMemoryError e) {
            closeWarmLlmLocked("OOM");
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "llm OOM", e);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "llm work failed", t);
        } finally {
            activeToken = null;
            scheduleCloseLocked();
            GATE.unlock();
        }
    }

    /**
     * {@code initialize()} on a dedicated thread with a hard deadline. The thread is <b>abandoned</b>
     * on timeout rather than interrupted: native code cannot be interrupted, and pretending otherwise
     * would deadlock the gate. The model is recorded as suspect so the UI can offer a smaller one.
     */
    private AiLlm openWithWatchdog(final AiModelInfo info, final boolean gpu) throws AiException {
        final File cacheDir = engineCacheDir(info.catalogId);
        final long t0 = System.currentTimeMillis();
        try {
            AiLlm llm = awaitLoad(new Callable<AiLlm>() {
                @Override
                public AiLlm call() throws AiException {
                    return AiEngines.openLlm(info, cacheDir, gpu);
                }
            }, LOAD_TIMEOUT_MS, info.catalogId);
            long ms = System.currentTimeMillis() - t0;
            Log.i(TAG, "loaded " + info.catalogId + " in " + ms + " ms");
            if (db != null) {
                db.putMetaLong("model_load_ms:" + info.catalogId, ms);
            }
            return llm;
        } catch (AiException e) {
            if (e.kind == AiException.Kind.LOAD_FAILED && e.getCause() instanceof TimeoutException
                    && db != null) {
                db.putMeta("model_suspect", info.catalogId);
            }
            throw e;
        }
    }

    /**
     * Runs {@code loader} on {@link #LOADER} with a hard deadline, and <b>guarantees that an engine
     * which finishes loading after the deadline is closed</b>.
     *
     * <h3>Why {@code future.cancel(true)} is not enough</h3>
     * {@code Engine.initialize()} is a blocking JNI call. An interrupt sets a flag on a thread that
     * is parked inside native code and will not look at it, so the load runs to completion anyway —
     * minutes later, with nobody waiting. The {@code Future} has already been abandoned, so the
     * constructed engine is never assigned to {@link #warmLlm} and never closed: multi-GB of mmapped
     * weights resident for the lifetime of the process, and the retry that follows loads a
     * <i>second</i> one on top of it.
     *
     * <h3>The handoff</h3>
     * The loader publishes the engine into {@code slot} and then checks {@code abandoned}; the
     * timeout path sets {@code abandoned} and then drains {@code slot}. Whichever side wins
     * {@code slot.getAndSet(null)} owns the close, so it happens <b>exactly once</b> whatever the
     * interleaving:
     * <ul>
     *   <li>loader publishes first &rarr; the timeout path drains and closes;</li>
     *   <li>timeout drains an empty slot first &rarr; the loader sees {@code abandoned}, drains its
     *       own publication and closes it on the loader thread;</li>
     *   <li>no timeout &rarr; {@code abandoned} stays false, the caller takes ownership through the
     *       {@code Future} and closes it the usual way.</li>
     * </ul>
     *
     * <p>Package-private and parameterised on the loader so a test can drive the late-completion
     * case without a 180 s wait or a real model.</p>
     *
     * @param timeoutMs deadline in milliseconds
     * @param label     model id, for the exception message
     */
    static AiLlm awaitLoad(final Callable<AiLlm> loader, long timeoutMs, final String label)
            throws AiException {
        final AtomicReference<AiLlm> slot = new AtomicReference<>();
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        Future<AiLlm> future = LOADER.submit(new Callable<AiLlm>() {
            @Override
            public AiLlm call() throws Exception {
                AiLlm llm = loader.call();
                if (llm == null) {
                    return null;
                }
                slot.set(llm);
                if (abandoned.get()) {
                    AiLlm late = slot.getAndSet(null);
                    if (late != null) {
                        closeQuietly(late, "loaded after the " + label + " watchdog fired");
                    }
                    throw new AiException(AiException.Kind.LOAD_FAILED,
                            label + " finished loading after the watchdog fired");
                }
                return llm;
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandoned.set(true);
            // Interrupting is best-effort and will not stop a blocking JNI call; the drain below
            // and the matching drain inside the loader are what actually free the engine.
            future.cancel(true);
            AiLlm late = slot.getAndSet(null);
            if (late != null) {
                closeQuietly(late, label + " raced the watchdog");
            }
            throw new AiException(AiException.Kind.LOAD_FAILED,
                    label + " did not load within " + timeoutMs + " ms", e);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            if (cause instanceof AiException) {
                throw (AiException) cause;
            }
            throw new AiException(AiException.Kind.LOAD_FAILED, "load failed", cause);
        }
    }

    private static void closeQuietly(AiLlm llm, String why) {
        Log.w(TAG, "closing an orphaned engine: " + why);
        try {
            llm.close();
        } catch (Throwable t) {
            Log.w(TAG, "orphaned engine close failed", t);
        }
    }

    private File engineCacheDir(String modelId) {
        File d = new File(new File(app.getCacheDir(), "litertlm"), modelId);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * Cancels anything in flight and closes the warm engine. Idempotent, safe from any thread, and
     * deliberately <b>does not block on the gate</b>: it is called from {@code onTrimMemory} on the
     * main thread while a worker holds it. Cancelling the token is what actually stops the run; the
     * worker's own {@code finally} then closes the engine.
     */
    public static void shutdownNow(String reason) {
        Log.i(TAG, "shutdownNow: " + reason);
        CancelToken token = activeToken;
        if (token != null) {
            token.cancel(reason);
        }
        if (GATE.tryLock()) {
            try {
                cancelPendingCloseLocked();
                closeWarmLlmLocked(reason);
            } finally {
                GATE.unlock();
            }
        }
    }

    /** True when an engine is currently resident or a run holds the gate. */
    public static boolean isBusy() {
        return GATE.isLocked() || warmLlm != null;
    }

    private static void closeWarmLlmLocked(String why) {
        if (warmLlm == null) {
            return;
        }
        Log.i(TAG, "closing " + warmModelId + " (" + why + ")");
        try {
            warmLlm.close();
        } catch (Throwable t) {
            Log.w(TAG, "engine close failed", t);
        }
        warmLlm = null;
        warmModelId = null;
    }

    private static void cancelPendingCloseLocked() {
        if (pendingClose != null) {
            pendingClose.cancel(false);
            pendingClose = null;
        }
    }

    private static void scheduleCloseLocked() {
        cancelPendingCloseLocked();
        if (warmLlm == null) {
            return;
        }
        pendingClose = KEEPALIVE.schedule(() -> {
            GATE.lock();
            try {
                closeWarmLlmLocked("idle " + IDLE_KEEPALIVE_MS + " ms");
            } finally {
                GATE.unlock();
            }
        }, IDLE_KEEPALIVE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers the memory-pressure listener exactly once, lazily — an application-wide callback
     * registered per manager instance would leak one listener per triage run.
     *
     * <p>{@code TRIM_MEMORY_UI_HIDDEN} is <b>ignored</b>. It fires every single time the user
     * backgrounds the app, which is precisely the moment we want to be running.
     */
    private void installTrimHandler() {
        if (app == null || !TRIM_HANDLER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            app.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                            || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                        shutdownNow("onTrimMemory(" + level + ")");
                    }
                }

                @Override
                public void onLowMemory() {
                    shutdownNow("onLowMemory");
                }

                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    // no-op
                }
            });
        } catch (Throwable t) {
            TRIM_HANDLER_INSTALLED.set(false);
            Log.w(TAG, "could not register memory callbacks", t);
        }
    }

    /**
     * Fail loud in development rather than as a 10 s ANR in the field: {@code initialize()} on a
     * ~184 MB (embedder) or multi-GB (LLM) model is never a main-thread operation.
     */
    private static void assertNotMainThread() {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "engine load attempted on the main thread");
            throw new IllegalStateException("AiEngineManager must not be used on the main thread");
        }
    }
}
