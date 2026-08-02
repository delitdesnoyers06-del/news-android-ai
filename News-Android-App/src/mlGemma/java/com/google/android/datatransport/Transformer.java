package com.google.android.datatransport;

/**
 * No-op stub. See {@code package-info.java}.
 *
 * <p>Must stay a <b>functional interface</b> (exactly one abstract method): MediaPipe supplies it
 * with a lambda through {@code invokedynamic}, so a second abstract method would break
 * {@code LambdaMetafactory} at link time.</p>
 *
 * <pre>32: invokedynamic #0:apply:()Lcom/google/android/datatransport/Transformer;</pre>
 *
 * <p>The lambda MediaPipe passes is {@code AbstractMessageLite::toByteArray} — serialising the
 * telemetry proto. Because nothing in this package ever calls {@link #apply}, that serialisation
 * never happens: the log payload is not merely undelivered, it is never even built.</p>
 *
 * @param <T> input type
 * @param <U> output type
 */
public interface Transformer<T, U> {

    U apply(T value);
}
