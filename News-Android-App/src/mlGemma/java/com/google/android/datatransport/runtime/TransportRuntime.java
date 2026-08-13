package com.google.android.datatransport.runtime;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;

/**
 * No-op stub. See {@code com.google.android.datatransport.package-info}.
 *
 * <p>This class is the whole point of the package: it is the entry point MediaPipe reaches
 * unconditionally from {@code TextEmbedder.createFromOptions}, and it is where the genuine library
 * would stand up its Dagger graph, its {@code SQLiteEventStore}, its {@code Uploader} and its
 * {@code JobScheduler}/{@code AlarmManager} work. Replacing it wholesale means none of that
 * machinery is ever built, so there is nothing behind it that could later be triggered.</p>
 *
 * <pre>
 *   RemoteLoggingClient.&lt;init&gt;
 *      8: invokestatic  TransportRuntime.initialize:(Landroid/content/Context;)V
 *     11: invokestatic  TransportRuntime.getInstance:()Lcom/google/android/datatransport/runtime/TransportRuntime;
 *     17: invokevirtual TransportRuntime.newFactory:(Lcom/google/android/datatransport/runtime/Destination;)Lcom/google/android/datatransport/TransportFactory;
 * </pre>
 *
 * <p>Note the {@code invokevirtual}: this must be a <b>class</b>, not an interface.</p>
 *
 * <p>{@link #getInstance()} and {@link #newFactory} must return non-null — MediaPipe chains
 * straight through both without a null check — which is why the singleton is initialised eagerly
 * and does not depend on {@link #initialize(Context)} having been called.</p>
 */
public class TransportRuntime {

    /**
     * Eager, and independent of {@link #initialize(Context)}: it holds nothing, so there is no
     * reason for {@link #getInstance()} to ever be able to return null.
     */
    private static final TransportRuntime INSTANCE = new TransportRuntime();

    private static final TransportFactory FACTORY = new NoOpTransportFactory();

    private TransportRuntime() {
        // no state, no resources, nothing to initialise
    }

    /**
     * Does nothing. The {@code Context} is intentionally not stored: keeping an application context
     * in a static would be a leak with no purpose, since nothing here can use it.
     */
    public static void initialize(Context context) {
        // Deliberately empty. See the class comment.
    }

    /** Never null. */
    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    /** Never null. Ignores {@code destination} — every destination is equally not-sent-to. */
    public TransportFactory newFactory(Destination destination) {
        return FACTORY;
    }

    /** Never null. Same contract as the {@link Destination} overload. */
    public TransportFactory newFactory(String backendName) {
        return FACTORY;
    }

    /** Hands out a {@link Transport} whose {@code send} discards its argument. */
    private static final class NoOpTransportFactory implements TransportFactory {

        @Override
        public <T> Transport<T> getTransport(String name, Class<T> payloadType, Encoding encoding,
                                             Transformer<T, byte[]> transformer) {
            return new NoOpTransport<>();
        }
    }

    /** The end of the line for every telemetry event this app's MediaPipe copy produces. */
    private static final class NoOpTransport<T> implements Transport<T> {

        @Override
        public void send(Event<T> event) {
            // Deliberately empty. The event is not queued, not stored, not transmitted, and the
            // Transformer that would have serialised it is never invoked.
        }
    }
}
