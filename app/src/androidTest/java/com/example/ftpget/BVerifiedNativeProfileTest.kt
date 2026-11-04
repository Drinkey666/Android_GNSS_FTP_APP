package com.example.ftpget

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the actual packaged .so through the Java/JNI boundary. */
@RunWith(AndroidJUnit4::class)
class BVerifiedNativeProfileTest {
    @Test fun packagedLiveLibraryUsesOnlyVerifiedPreset() {
        val profile = LivePppNative.nativeProfile()
        assertTrue(profile, profile.contains("repairs=B_VERIFIED_v2"))
        assertTrue(profile, profile.contains("covariance=JOSEPH_B_MASK"))
        assertTrue(profile, profile.contains("clock_seed=PPP_COMMON"))
        assertTrue(profile, profile.contains("NFREQ=4"))
        assertTrue(profile, profile.contains("navsys=41"))
        assertTrue(profile, profile.contains("phase_identity=1"))
        assertTrue(profile, profile.contains("ion_continuity=1"))
        assertTrue(profile, profile.contains("reference_seed=1"))
    }
}
