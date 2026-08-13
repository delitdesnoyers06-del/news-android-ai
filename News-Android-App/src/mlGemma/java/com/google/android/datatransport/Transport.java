package com.google.android.datatransport;

/**
 * No-op stub. See {@code package-info.java}.
 *
 * <p><b>This is the interface where the telemetry would have left the device.</b> The only
 * implementation in this app is the one returned by {@code TransportRuntime}'s stub factory, and
 * its {@code send} does nothing at all — no buffering, no queue, no disk, no network, no
 * {@code JobScheduler}.</p>
 *
 * <pre>8: invokeinterface Transport.send:(Lcom/google/android/datatransport/Event;)V</pre>
 *
 * <p>{@code schedule(Event, TransportScheduleCallback)} exists on the real interface but is never
 * referenced by MediaPipe, so it is deliberately absent — adding it would drag in
 * {@code TransportScheduleCallback} for nothing.</p>
 *
 * @param <T> payload type
 */
public interface Transport<T> {

    /** Discards {@code event}. */
    void send(Event<T> event);
}
