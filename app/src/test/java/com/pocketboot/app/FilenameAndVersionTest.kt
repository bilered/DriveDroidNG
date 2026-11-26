package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameAndVersionTest {

    @Test
    fun sanitizeOutputFileName_stripsTraversalAndEnforcesExtension() {
        assertEquals("evil.img", UsbGadgetEngine.sanitizeOutputFileName("../../evil", ".img"))
        assertEquals("clean.iso", UsbGadgetEngine.sanitizeOutputFileName("C:\\Windows\\..\\clean.iso", ".iso"))
        assertEquals("fallback.img", UsbGadgetEngine.sanitizeOutputFileName("...***???", ".img", "fallback"))
        assertEquals("Win11.img", UsbGadgetEngine.sanitizeOutputFileName("Win11.img", ".img"))
    }

    @Test
    fun isoInspector_sanitizesLabelsAndDetectsOsHints() {
        assertEquals("POCKETBOOT", IsoInspector.sanitizeFatLabel("///:::***", fallback = "POCKETBOOT"))
        assertEquals("UBUNTU_2604", IsoInspector.sanitizeFatLabel("Ubuntu_2604_Long_Label"))
        assertEquals(IsoOsType.WINDOWS, IsoInspector.quickGuessByName("Win11_26H2_English_x64.iso"))
        assertEquals(IsoOsType.VENTOY_IMAGE, IsoInspector.quickGuessByName("POCKETBOOT Multi ISO.img"))
        assertEquals(IsoOsType.LINUX, IsoInspector.quickGuessByName("ubuntu-26.04.1-desktop-amd64.iso"))
    }

    @Test
    fun appUpdateChecker_comparesSemVerAccurately() {
        assertTrue(AppUpdateChecker.compareSemVer("v4.7.1", "4.7.0") > 0)
        assertTrue(AppUpdateChecker.compareSemVer("4.10.0", "4.7.0") > 0)
        assertEquals(0, AppUpdateChecker.compareSemVer("v4.7.0", "4.7.0"))
        assertTrue(AppUpdateChecker.compareSemVer("4.6.9", "4.7.0") < 0)
        assertTrue(AppUpdateChecker.compareSemVer("4.7.0", "4.7.0-rc1") > 0)
        assertEquals("https://github.com/bilered/PocketBoot", AppUpdateChecker.REPO_URL)
    }
}
