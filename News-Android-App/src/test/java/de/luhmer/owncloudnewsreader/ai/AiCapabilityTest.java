package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

/**
 * The device gate that decides whether the "For you" drawer row exists at all.
 *
 * <p>Only the parts Phase 2 depends on are covered here — the per-model gates land with the model
 * manager. Note that unit tests run against {@code BuildConfig.DEBUG == true}, which is exactly the
 * regime where an x86_64-only device is deliberately accepted.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiCapabilityTest {

    private static final long GB = 1024L * 1024L * 1024L;

    private final String[] originalAbis = Build.SUPPORTED_64_BIT_ABIS;

    @After
    public void restoreAbis() {
        setAbis(originalAbis);
    }

    @Test
    public void arm64WithPlentyOfRamIsFullTier() {
        setAbis(new String[]{"arm64-v8a"});
        setTotalRam(8 * GB);

        assertTrue(AiCapability.hasArm64());
        assertEquals(AiCapability.Tier.FULL, AiCapability.tier(context()));
        assertTrue(AiCapability.isSupported(context()));
    }

    @Test
    public void threeToSixGigabytesIsLightTier() {
        setAbis(new String[]{"arm64-v8a"});
        setTotalRam(4 * GB);

        assertEquals(AiCapability.Tier.LIGHT, AiCapability.tier(context()));
        assertTrue(AiCapability.isSupported(context()));
    }

    @Test
    public void justBelowThreeGigabytesIsUnsupported() {
        setAbis(new String[]{"arm64-v8a"});
        setTotalRam(3 * GB - 1);

        assertEquals(AiCapability.Tier.UNSUPPORTED, AiCapability.tier(context()));
        assertFalse("the drawer row must not exist on a sub-3GB device",
                AiCapability.isSupported(context()));
    }

    @Test
    public void exactlySixGigabytesIsFullTier() {
        setAbis(new String[]{"arm64-v8a"});
        setTotalRam(6 * GB);

        assertEquals(AiCapability.Tier.FULL, AiCapability.tier(context()));
    }

    @Test
    public void aThirtyTwoBitDeviceIsUnsupportedNoMatterHowMuchRamItHas() {
        setAbis(new String[0]);
        setTotalRam(16 * GB);

        assertFalse(AiCapability.hasArm64());
        assertEquals(AiCapability.Tier.UNSUPPORTED, AiCapability.tier(context()));
    }

    @Test
    public void x8664IsAcceptedInDebugBuildsOnly() {
        setAbis(new String[]{"x86_64"});
        setTotalRam(8 * GB);

        assertFalse(AiCapability.hasArm64());
        assertTrue(AiCapability.hasX8664());
        // Unit tests are a debug build, so the emulator path is the one exercised here.
        assertEquals(AiCapability.Tier.FULL, AiCapability.tier(context()));
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static void setAbis(String[] abis) {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_64_BIT_ABIS", abis);
    }

    private static void setTotalRam(long bytes) {
        ActivityManager am = (ActivityManager) context().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        mi.totalMem = bytes;
        mi.availMem = bytes / 2;
        mi.threshold = 128L * 1024 * 1024;
        mi.lowMemory = false;
        Shadows.shadowOf(am).setMemoryInfo(mi);
    }
}
