package de.luhmer.owncloudnewsreader.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import de.luhmer.owncloudnewsreader.BuildConfig;

/**
 * "Can this device run the AI half at all?" — the single device gate (PLAN D9 / D24).
 *
 * <p>ABI is the hard gate, RAM the soft one. {@code litertlm-android} ships <em>only</em>
 * {@code arm64-v8a} and {@code x86_64} native libraries, so anything else would reach
 * {@code System.loadLibrary} and die with an {@code UnsatisfiedLinkError} inside a background
 * service — which the user experiences as "the AI folder is permanently empty". x86_64 is
 * accepted in debug builds only, so {@code connectedAndroidTest} and emulators still work.</p>
 *
 * <p>Tiering uses {@link ActivityManager.MemoryInfo#totalMem} — total physical RAM, stable across
 * launches. {@code availMem} flaps and {@code getMemoryClass()} bounds the <em>Java heap</em>,
 * which says nothing about a natively mmapped multi-GB model; using either would be wrong.</p>
 *
 * <p>Everything here is static and Android-only-by-necessity: no AI runtime type is referenced,
 * so this class compiles and behaves identically in the {@code mlNone} flavor.</p>
 */
public final class AiCapability {

    /** Device tiers. T0 = UNSUPPORTED, T1 = LIGHT (3-6 GB), T2 = FULL (>= 6 GB). */
    public enum Tier { UNSUPPORTED, LIGHT, FULL }

    private static final long GB = 1024L * 1024L * 1024L;

    static final long LIGHT_MIN_RAM_BYTES = 3 * GB;
    static final long FULL_MIN_RAM_BYTES = 6 * GB;

    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_X86_64 = "x86_64";

    private AiCapability() {
        // no instances
    }

    /** True iff a 64-bit ARM ABI is present. There is no override for a false here. */
    public static boolean hasArm64() {
        return hasAbi(ABI_ARM64);
    }

    /** True iff a 64-bit x86 ABI is present (emulators; accepted in debug builds only). */
    public static boolean hasX8664() {
        return hasAbi(ABI_X86_64);
    }

    private static boolean hasAbi(String wanted) {
        String[] abis = Build.SUPPORTED_64_BIT_ABIS;
        if (abis == null) {
            return false;
        }
        for (String abi : abis) {
            if (wanted.equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /** True iff the native libraries we ship can actually be loaded on this device. */
    public static boolean abiSupported() {
        return hasArm64() || (BuildConfig.DEBUG && hasX8664());
    }

    /** Total physical RAM in bytes, or 0 when it cannot be determined. */
    public static long totalRam(Context context) {
        ActivityManager am = activityManager(context);
        if (am == null) {
            return 0L;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    public static Tier tier(Context context) {
        if (!abiSupported()) {
            return Tier.UNSUPPORTED;
        }
        long ram = totalRam(context);
        if (ram < LIGHT_MIN_RAM_BYTES) {
            return Tier.UNSUPPORTED;
        }
        if (ram < FULL_MIN_RAM_BYTES) {
            return Tier.LIGHT;
        }
        return Tier.FULL;
    }

    /**
     * The drawer-row / settings-row gate. On an unsupported device the "For you" row simply does
     * not exist — no greyed-out entry, no explanatory dialog, nothing to degrade.
     */
    public static boolean isSupported(Context context) {
        return tier(context) != Tier.UNSUPPORTED;
    }

    /**
     * Per-model RAM gate. {@code minTotalRamBytes} is a catalogue data field, never a formula
     * derived from the file size (PLAN D24).
     */
    public static boolean selectOk(Context context, long minTotalRamBytes) {
        return totalRam(context) >= minTotalRamBytes;
    }

    /** Headroom demanded on the volume that holds the weights, on top of the file itself. */
    public static final long DOWNLOAD_HEADROOM_BYTES = 256L * 1024 * 1024;
    /** Headroom demanded on <b>internal</b> storage, where the compiled engine cache lands. */
    public static final long INTERNAL_HEADROOM_BYTES = 500L * 1024 * 1024;

    /**
     * The disk gate (PLAN D24) — <b>two volumes, not one</b>.
     *
     * <p>{@code .part -> final} is a {@code renameTo} on the same filesystem, so there is no 1.15x
     * copy factor to pay: {@code size + 256 MB} on the external volume is right. What a single-volume
     * rule misses is that the smoke load and every later {@code initialize()} write the rearranged
     * weights to <b>internal</b> {@code getCacheDir()} — a different volume whose exhaustion is the
     * check that actually fires on a full phone.</p>
     */
    public static boolean downloadOk(Context context, long sizeBytes) {
        if (context == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        long external = freeBytes(app.getExternalFilesDir(null));
        long internal = freeBytes(app.getCacheDir());
        return external >= sizeBytes + DOWNLOAD_HEADROOM_BYTES && internal >= INTERNAL_HEADROOM_BYTES;
    }

    /** Free bytes on the volume holding {@code dir}, or 0 when it cannot be determined. */
    public static long freeBytes(java.io.File dir) {
        if (dir == null) {
            return 0L;
        }
        try {
            android.os.StatFs fs = new android.os.StatFs(dir.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Runtime defer signal only — NEVER a tiering signal. {@code availMem} is volatile by design.
     */
    public static boolean lowMemoryRightNow(Context context) {
        ActivityManager am = activityManager(context);
        if (am == null) {
            return false;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.lowMemory || mi.availMem < mi.threshold * 2;
    }

    private static ActivityManager activityManager(Context context) {
        if (context == null) {
            return null;
        }
        return (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
    }
}
