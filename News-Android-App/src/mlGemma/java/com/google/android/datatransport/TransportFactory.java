package com.google.android.datatransport;

/**
 * No-op stub. See {@code package-info.java}.
 *
 * <p>Only the four-argument overload is referenced; the three-argument one on the real interface is
 * deliberately omitted.</p>
 *
 * <pre>37: invokeinterface TransportFactory.getTransport:(Ljava/lang/String;Ljava/lang/Class;Lcom/google/android/datatransport/Encoding;Lcom/google/android/datatransport/Transformer;)Lcom/google/android/datatransport/Transport;</pre>
 *
 * <p>The returned {@link Transport} is stored in a field by {@code RemoteLoggingClient} and has
 * {@code send} called on it, so implementations <b>must not</b> return null.</p>
 */
public interface TransportFactory {

    /** Must never return null: the caller dereferences the result on every log event. */
    <T> Transport<T> getTransport(String name, Class<T> payloadType, Encoding encoding,
                                  Transformer<T, byte[]> transformer);
}
