package com.google.android.datatransport.runtime;

/**
 * No-op stub. See {@code com.google.android.datatransport.package-info}.
 *
 * <p>Never referenced by name in MediaPipe's bytecode, but structurally required: MediaPipe passes
 * {@code CCTDestination.INSTANCE} to
 * {@code TransportRuntime.newFactory(Lcom/google/android/datatransport/runtime/Destination;)}, so
 * the verifier needs {@code CCTDestination} to be assignable to this type.</p>
 *
 * <pre>17: invokevirtual TransportRuntime.newFactory:(Lcom/google/android/datatransport/runtime/Destination;)Lcom/google/android/datatransport/TransportFactory;</pre>
 */
public interface Destination {

    String getName();

    byte[] getExtras();
}
