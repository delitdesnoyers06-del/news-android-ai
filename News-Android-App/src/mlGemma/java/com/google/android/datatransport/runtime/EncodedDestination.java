package com.google.android.datatransport.runtime;

import com.google.android.datatransport.Encoding;

import java.util.Set;

/**
 * No-op stub. See {@code com.google.android.datatransport.package-info}.
 *
 * <p>Present only so {@code CCTDestination} can declare the same hierarchy as the real one
 * ({@code CCTDestination implements EncodedDestination extends Destination}).</p>
 */
public interface EncodedDestination extends Destination {

    Set<Encoding> getSupportedEncodings();
}
