package com.google.android.datatransport.cct;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.runtime.EncodedDestination;

import java.util.Collections;
import java.util.Set;

/**
 * No-op stub. See {@code com.google.android.datatransport.package-info}.
 *
 * <p>CCT is Google's Clearcut backend. In the genuine artifact this class carries the upload
 * endpoint and API key; here it carries neither, and the only member MediaPipe touches is
 * {@link #INSTANCE}, which it passes to
 * {@code TransportRuntime.newFactory(Destination)} — a method that ignores it.</p>
 *
 * <pre>14: getstatic CCTDestination.INSTANCE:Lcom/google/android/datatransport/cct/CCTDestination;</pre>
 *
 * <p>{@code LEGACY_INSTANCE}, {@code getEndPoint()}, {@code getAPIKey()}, {@code asByteArray()} and
 * {@code fromByteArray(byte[])} exist on the real class and are deliberately absent here: nothing
 * references them, and an endpoint constant has no business being in this repository.</p>
 */
public final class CCTDestination implements EncodedDestination {

    /** The only member MediaPipe reads. Must be non-null. */
    public static final CCTDestination INSTANCE = new CCTDestination();

    private CCTDestination() {
        // no endpoint, no API key, nothing
    }

    @Override
    public String getName() {
        return "cct";
    }

    @Override
    public byte[] getExtras() {
        // Not null-vs-empty by accident: the real implementation encodes the endpoint and API key
        // here. There is nothing to encode.
        return null;
    }

    @Override
    public Set<Encoding> getSupportedEncodings() {
        return Collections.singleton(Encoding.of("proto"));
    }
}
