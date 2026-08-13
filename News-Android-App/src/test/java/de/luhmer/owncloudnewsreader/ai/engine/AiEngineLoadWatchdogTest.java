package de.luhmer.owncloudnewsreader.ai.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEEPREVIEW F3 — an engine that finishes loading <b>after</b> the watchdog fired must still be
 * closed, exactly once.
 *
 * <p>{@code Engine.initialize()} is a blocking JNI call, so {@code future.cancel(true)} does not
 * stop it: the load runs to completion minutes later with nobody waiting. Before the fix the
 * resulting engine was never assigned and never closed, leaving multi-GB of mmapped weights
 * resident for the lifetime of the process — and the retry that follows loaded a second one on top.
 * These tests drive {@link AiEngineManager#awaitLoad} directly with a slow fake loader, so the
 * whole handoff is exercised on a plain JVM in a few hundred milliseconds.</p>
 */
public class AiEngineLoadWatchdogTest {

    private static final long TIMEOUT_MS = 120L;

    /** Counts its own closes so "exactly once" is assertable. */
    private static final class CountingLlm implements AiLlm {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public AiModelInfo model() {
            return new AiModelInfo("fake", "Fake", 1L, new File("/dev/null"), true, 2048);
        }

        @Override
        public boolean supportsConstrainedDecoding() {
            return true;
        }

        @Override
        public AiConversation start(AiPromptSpec spec) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    @Test
    public void anEngineThatArrivesAfterTheWatchdogIsClosedExactlyOnce() throws Exception {
        final CountingLlm engine = new CountingLlm();
        final CountDownLatch loaderEntered = new CountDownLatch(1);

        try {
            AiEngineManager.awaitLoad(() -> {
                loaderEntered.countDown();
                // Stands in for a blocking, uninterruptible initialize() that overruns the
                // deadline. Deliberately NOT interruptible-aware: that is the whole point.
                long until = System.currentTimeMillis() + (TIMEOUT_MS * 6);
                while (System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignored) {
                        // A real JNI call would not even see this. Keep going.
                    }
                }
                return engine;
            }, TIMEOUT_MS, "late-model");
            fail("expected the watchdog to fire");
        } catch (AiException e) {
            assertEquals(AiException.Kind.LOAD_FAILED, e.kind);
            assertTrue("message should name the deadline: " + e.getMessage(),
                    e.getMessage().contains("did not load within"));
        }

        assertTrue("the loader must actually have run", loaderEntered.await(2, TimeUnit.SECONDS));
        assertEquals("not closed yet - the loader is still blocked", 0, engine.closes.get());

        // The loader completes long after the caller gave up. Nothing is waiting on it, so if the
        // handoff is broken this engine simply leaks and the latch never trips.
        assertTrue("a late-completing engine must be closed",
                engine.closed.await(5, TimeUnit.SECONDS));
        // Give both drain paths room to run before asserting "once" rather than "at least once".
        Thread.sleep(200L);
        assertEquals("closed exactly once", 1, engine.closes.get());
    }

    @Test
    public void aLoadInsideTheDeadlineIsHandedToTheCallerAndNotClosed() throws Exception {
        CountingLlm engine = new CountingLlm();
        AiLlm loaded = AiEngineManager.awaitLoad(() -> engine, TIMEOUT_MS, "fast-model");
        assertSame(engine, loaded);
        Thread.sleep(150L);
        assertEquals("the caller owns it now", 0, engine.closes.get());
        assertFalse(engine.closed.await(1, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aLoaderFailureSurfacesItsOwnAiException() {
        try {
            AiEngineManager.awaitLoad(() -> {
                throw new AiException(AiException.Kind.OUT_OF_MEMORY, "engine load OOM");
            }, TIMEOUT_MS, "oom-model");
            fail("expected the loader's exception");
        } catch (AiException e) {
            assertEquals(AiException.Kind.OUT_OF_MEMORY, e.kind);
        }
    }

    @Test
    public void aLoaderThatReturnsNullIsNotATimeout() throws Exception {
        assertEquals(null, AiEngineManager.awaitLoad(() -> null, TIMEOUT_MS, "null-model"));
    }
}
