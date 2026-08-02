package com.google.android.datatransport;

/**
 * No-op stub. See {@code package-info.java}.
 *
 * <p>MediaPipe calls the single-argument factory and hands the result straight to
 * {@link Transport#send}; it never reads the payload back.</p>
 *
 * <pre>5: invokestatic Event.ofData:(Ljava/lang/Object;)Lcom/google/android/datatransport/Event;</pre>
 *
 * <p>The real {@code Event} is abstract with an AutoValue subclass. Concrete here, because there is
 * nothing to model: the instance is created and immediately dropped by
 * {@link Transport#send(Event)}. It does hold the payload reference so it is not misleading under
 * a debugger, but nothing reads it and nothing retains the event.</p>
 *
 * @param <T> payload type
 */
public class Event<T> {

    private final T payload;

    private Event(T payload) {
        this.payload = payload;
    }

    /** Never null. The event is discarded unread by the no-op {@link Transport}. */
    public static <T> Event<T> ofData(T payload) {
        return new Event<>(payload);
    }

    public T getPayload() {
        return payload;
    }
}
