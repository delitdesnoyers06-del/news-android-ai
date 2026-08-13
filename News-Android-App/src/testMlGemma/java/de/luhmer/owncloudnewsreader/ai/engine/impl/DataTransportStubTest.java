package de.luhmer.owncloudnewsreader.ai.engine.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.cct.CCTDestination;
import com.google.android.datatransport.runtime.Destination;
import com.google.android.datatransport.runtime.TransportRuntime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEEPREVIEW B2 — proves the {@code com.google.android.datatransport} no-op stubs actually satisfy
 * MediaPipe.
 *
 * <p>The exclusion in {@code build.gradle} is what makes the {@code mlGemma} flavor honest about
 * {@code PRIVACY.md}, but MediaPipe reaches {@code TransportRuntime.initialize(Context)}
 * unconditionally from {@code TextEmbedder.createFromOptions}. Without the stubs that is a
 * {@code NoClassDefFoundError} on the first embedding call, mapped to {@code LOAD_FAILED} and
 * therefore <b>silent</b>. These tests run MediaPipe's own classes against the stubs.</p>
 *
 * <p><b>mlGemma only</b> ({@code src/testMlGemma}): the stubs and MediaPipe do not exist in
 * {@code mlNone}, and must not.</p>
 *
 * <h3>What this can and cannot prove on the JVM</h3>
 * It proves resolution and behaviour of the whole logging chain, including MediaPipe's real
 * {@code RemoteLoggingClient} and {@code TasksStatsLoggerFactory}. It cannot prove the rest of
 * {@code TextEmbedder.createFromOptions}, which needs {@code libmediapipe_tasks_jni.so} and
 * therefore a device — see {@link #textEmbedderGetsPastTheLoggingHop()}, which asserts exactly the
 * boundary: past datatransport, stopped by the native library.
 */
@RunWith(RobolectricTestRunner.class)
public class DataTransportStubTest {

    private static Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    /** The literal reflective check: the class resolves and {@code initialize(Context)} runs. */
    @Test
    public void transportRuntimeResolvesAndInitializeIsCallable() throws Exception {
        Class<?> runtime = Class.forName("com.google.android.datatransport.runtime.TransportRuntime");
        Method initialize = runtime.getMethod("initialize", Context.class);
        initialize.invoke(null, ctx());     // must not throw
        Method getInstance = runtime.getMethod("getInstance");
        assertNotNull("getInstance() is dereferenced by MediaPipe", getInstance.invoke(null));
    }

    /** Every hop of {@code RemoteLoggingClient.<init>}, in the order the bytecode does them. */
    @Test
    public void theStubChainMediaPipeWalksNeverReturnsNullAndNeverThrows() {
        TransportRuntime.initialize(ctx().getApplicationContext());
        TransportRuntime runtime = TransportRuntime.getInstance();
        assertNotNull(runtime);

        Destination destination = CCTDestination.INSTANCE;
        assertNotNull("getstatic CCTDestination.INSTANCE", destination);

        TransportFactory factory = runtime.newFactory(destination);
        assertNotNull("newFactory(Destination) is dereferenced immediately", factory);

        Transport<String> transport = factory.getTransport(
                "COREML_ON_DEVICE_SOLUTIONS", String.class, Encoding.of("proto"),
                String::getBytes);
        assertNotNull("getTransport(...) is stored in a field and used on every log event",
                transport);

        transport.send(Event.ofData("would have been telemetry"));  // must be a no-op, not a throw
    }

    /** A stub that transmitted would be worse than one that crashed. Prove it does neither. */
    @Test
    public void theTransformerIsNeverInvoked() {
        final AtomicInteger transformerCalls = new AtomicInteger();
        Transport<String> transport = TransportRuntime.getInstance()
                .newFactory(CCTDestination.INSTANCE)
                .getTransport("COREML_ON_DEVICE_SOLUTIONS", String.class, Encoding.of("proto"),
                        value -> {
                            transformerCalls.incrementAndGet();
                            return value.getBytes();
                        });
        for (int i = 0; i < 100; i++) {
            transport.send(Event.ofData("payload " + i));
        }
        assertEquals("the payload must never even be serialised", 0, transformerCalls.get());
        assertSame("the destination is ignored, not dispatched on",
                CCTDestination.INSTANCE, CCTDestination.INSTANCE);
    }

    /**
     * <b>The real proof.</b> MediaPipe's own {@code RemoteLoggingClient} — the single class in
     * {@code tasks-core} that touches datatransport — constructed for real. Its constructor is the
     * exact bytecode that was dying with {@code NoClassDefFoundError}.
     */
    @Test
    public void mediaPipeRemoteLoggingClientConstructsAgainstTheStubs() throws Exception {
        Class<?> clientClass =
                Class.forName("com.google.mediapipe.tasks.core.logging.RemoteLoggingClient");
        Object client = clientClass.getConstructor(Context.class).newInstance(ctx());
        assertNotNull(client);

        // The transport field is what getTransport(...) returned. Non-null means the whole chain
        // ran: initialize -> getInstance -> newFactory -> getTransport.
        java.lang.reflect.Field transport = clientClass.getDeclaredField("transport");
        transport.setAccessible(true);
        assertNotNull("RemoteLoggingClient.transport must be populated", transport.get(client));
    }

    /**
     * The hop {@code TaskRunner.create} makes as its very first statement, called directly.
     * {@code TaskRunner.create} itself needs the native graph; this does not.
     */
    @Test
    public void tasksStatsLoggerFactoryCreatesAgainstTheStubs() throws Exception {
        Class<?> factoryClass =
                Class.forName("com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory");
        Method create = factoryClass.getMethod("create", Context.class, String.class, String.class);
        Object logger = create.invoke(null, ctx(), "TextEmbedder", "IMAGE");
        assertNotNull("TasksStatsLoggerFactory.create must return a logger", logger);
    }

    /**
     * <b>The end-to-end proof, as far as a JVM can take it:</b> the real
     * {@code TextEmbedder.createFromOptions} runs <i>past</i> the entire datatransport path and
     * stops at the first thing a JVM genuinely cannot do — loading
     * {@code libmediapipe_tasks_jni.so}.
     *
     * <p>That boundary is exact, not lucky. {@code TaskRunner}'s static initialiser loads no native
     * library (its {@code <clinit>} is four instructions, all {@code Class.getSimpleName}), and
     * {@code TaskRunner.create}'s <i>first statement</i> is
     * {@code invokestatic TasksStatsLoggerFactory.create} at offset 9 — which is what reaches
     * {@code TransportRuntime.initialize}. So an {@code UnsatisfiedLinkError} on the JNI library
     * can only be reached by a call that already completed the logging chain. Before this fix the
     * same call died earlier, with {@code NoClassDefFoundError:
     * com.google.android.datatransport.runtime.TransportRuntime}.</p>
     *
     * <p>Asserting the exact boundary rather than merely "no datatransport error" is deliberate: a
     * future change that made {@code createFromOptions} fail <i>before</i> the logging hop would
     * quietly stop testing anything, and this assertion is what catches that. Both MediaPipe
     * artifacts are pinned to 1.0.0, so the boundary does not move on its own.</p>
     *
     * <p><b>Still needs a device:</b> everything after this line — the graph, the JNI binding
     * itself, and whether the real {@code embedding_gemma.task} loads and embeds correctly (spikes
     * S1/S4/S8).</p>
     */
    @Test
    public void textEmbedderGetsPastTheLoggingHop() {
        Throwable failure = null;
        try {
            com.google.mediapipe.tasks.text.textembedder.TextEmbedder.createFromOptions(
                    ctx(),
                    com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
                            .builder()
                            .setBaseOptions(com.google.mediapipe.tasks.core.BaseOptions.builder()
                                    .setModelAssetPath("/nonexistent/embedding_gemma.task")
                                    .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                                    .build())
                            .setL2Normalize(true)
                            .setQuantize(false)
                            .build());
        } catch (Throwable t) {
            failure = t;
        }
        if (failure == null) {
            return;     // it got even further than expected; the logging hop certainly ran
        }
        assertNoMissingDataTransportClass(failure);
        assertTrue("expected to be stopped by the JNI library, i.e. past the datatransport path,"
                        + " but got: " + chainOf(failure),
                chainContains(failure, UnsatisfiedLinkError.class, "mediapipe_tasks_jni"));
    }

    /** True when some link in the cause chain is {@code type} and mentions {@code needle}. */
    private static boolean chainContains(Throwable t, Class<? extends Throwable> type,
                                         String needle) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c) && String.valueOf(c.getMessage()).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The whole cause chain, flattened, for assertion messages. */
    private static String chainOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" || ");
        }
        return sb.toString();
    }

    /**
     * Walks the whole cause chain looking for the failure mode this fix exists to prevent. A
     * missing native library, a missing model file or a graph error are all acceptable here; a
     * missing {@code com.google.android.datatransport} class is not.
     */
    private static void assertNoMissingDataTransportClass(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            boolean linkage = c instanceof NoClassDefFoundError
                    || c instanceof ClassNotFoundException
                    || c instanceof NoSuchMethodError
                    || c instanceof NoSuchFieldError
                    || c instanceof IncompatibleClassChangeError;
            String message = String.valueOf(c.getMessage());
            if (linkage && message.contains("datatransport")) {
                fail("the datatransport stubs did not satisfy MediaPipe: "
                        + c.getClass().getName() + ": " + message);
            }
            assertTrue("unexpected datatransport linkage failure: " + c,
                    !linkage || !message.contains("datatransport"));
        }
    }
}
