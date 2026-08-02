package com.google.android.datatransport;

/**
 * No-op stub. See {@code package-info.java}.
 *
 * <p>MediaPipe calls {@code Encoding.of("proto")} once and passes the result straight to
 * {@link TransportFactory#getTransport}; it never reads it back. Kept as a real value object
 * anyway, because it costs nothing and keeps the stub honest against the genuine API.</p>
 *
 * <pre>27: ldc "proto" / 29: invokestatic Encoding.of:(Ljava/lang/String;)Lcom/google/android/datatransport/Encoding;</pre>
 */
public final class Encoding {

    private final String name;

    private Encoding(String name) {
        this.name = name;
    }

    /** Never null, so a caller that does dereference it is safe. */
    public static Encoding of(String name) {
        return new Encoding(name == null ? "" : name);
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Encoding && name.equals(((Encoding) other).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "Encoding{name=\"" + name + "\"}";
    }
}
