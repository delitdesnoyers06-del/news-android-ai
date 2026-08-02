/**
 * <b>Deliberate no-op stubs for Google's {@code datatransport} (Clearcut) client.</b> Nothing in
 * this package or its subpackages transmits, stores, queues or schedules anything, ever.
 *
 * <h2>Why this package exists</h2>
 * {@code News-Android-App/build.gradle} excludes {@code com.google.android.datatransport} from
 * {@code com.google.mediapipe:tasks-text}. That exclusion is the reason the {@code mlGemma} flavor
 * split is worth having: {@code PRIVACY.md} promises nothing leaves the device, and shipping
 * Google's telemetry uploader inside the AI variant would make that promise false no matter what
 * the app itself does.
 *
 * <p>MediaPipe does not treat its logger as optional. {@code TextEmbedder.createFromOptions}
 * reaches {@code TransportRuntime.initialize(Context)} through four unconditional hops with no
 * try/catch anywhere along the way (DEEPREVIEW B2), verified in the 1.0.0 bytecode:</p>
 * <pre>
 *   TextEmbedder.createFromOptions
 *     60: invokestatic  TaskRunner.create
 *   TaskRunner.create                      // first statement of the method
 *      9: invokestatic  TasksStatsLoggerFactory.create
 *   TasksStatsLoggerFactory.create
 *      3: invokestatic  TasksStatsProtoLogger.create
 *   TasksStatsProtoLogger.&lt;init&gt;              // no Exception table on this method
 *    100: new           RemoteLoggingClient
 *    105: invokespecial RemoteLoggingClient."&lt;init&gt;":(Landroid/content/Context;)V
 *   RemoteLoggingClient.&lt;init&gt;
 *      8: invokestatic  TransportRuntime.initialize:(Landroid/content/Context;)V
 * </pre>
 * So with the artifact simply gone, the first embedding call dies with a
 * {@code NoClassDefFoundError}, which {@code MediaPipeEmbedder.open} maps to
 * {@code AiException.LOAD_FAILED} — the whole embedding and similarity half of the feature dead,
 * silently, on every device.
 *
 * <h2>What this is and is not</h2>
 * This is <b>not</b> shadowing a MediaPipe class. It supplies the excluded library's own public API
 * at its own coordinates, as a no-op, so the classes resolve and the code path completes. The
 * alternative — dropping the exclusion — would ship a real uploader with a real endpoint, a real
 * SQLite event store and real {@code JobScheduler} work, and would only be prevented from sending
 * by us hoping nothing calls it.
 *
 * <h2>The exact surface, and why it is exactly this</h2>
 * {@code grep -rla datatransport} over all 778 classes of {@code tasks-core-1.0.0} matches
 * <b>one</b> file, {@code com.google.mediapipe.tasks.core.logging.RemoteLoggingClient}
 * ({@code tasks-text-1.0.0} matches none). Its two methods reference twelve members in total, and
 * this package provides those and their transitive requirements — no more. Signatures were taken
 * from the real {@code transport-api} / {@code transport-runtime} / {@code transport-backend-cct}
 * artifacts, not guessed, so a stub is assignment- and descriptor-compatible with the genuine one.
 *
 * <p>Three return values are dereferenced by MediaPipe and therefore must be non-null, or a stub
 * turns a silent no-op into an NPE: {@code TransportRuntime.getInstance()},
 * {@code TransportRuntime.newFactory(Destination)} and
 * {@code TransportFactory.getTransport(...)}.</p>
 *
 * <h2>Rules for anyone editing this package</h2>
 * <ol>
 *   <li><b>Never throw.</b> Not {@code UnsupportedOperationException}, not an assertion. Every
 *       method here is on a path MediaPipe calls unconditionally; an exception is the outage this
 *       package exists to prevent.</li>
 *   <li><b>Never do I/O, never touch the network, never persist, never schedule work.</b></li>
 *   <li>Do not add members "for completeness". The surface is pinned to what the bytecode shows;
 *       if a MediaPipe upgrade references something new, re-derive it from the bytecode and add
 *       only that.</li>
 *   <li>If a future dependency (Firebase, play-services) drags the genuine
 *       {@code com.google.android.datatransport} artifacts back onto the classpath, the build will
 *       fail with a duplicate-class error rather than silently preferring one. That failure is the
 *       intended alarm: exclude the transitive dependency again, do not delete these stubs.</li>
 * </ol>
 *
 * <p><b>Flavor scope:</b> {@code src/mlGemma} only. {@code mlNone} has no MediaPipe and therefore
 * must not contain a single class of this package.</p>
 */
package com.google.android.datatransport;
