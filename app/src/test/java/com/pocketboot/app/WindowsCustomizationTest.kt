package com.pocketboot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class WindowsCustomizationTest {

    @Test
    fun sanitizeWindowsUsername_blocksReservedAndIllegalChars() {
        assertEquals("", WindowsCustomizationEngine.sanitizeWindowsUsername("Administrator"))
        assertEquals("", WindowsCustomizationEngine.sanitizeWindowsUsername("CON"))
        assertEquals("", WindowsCustomizationEngine.sanitizeWindowsUsername("NUL"))
        assertEquals("ShadabUser", WindowsCustomizationEngine.sanitizeWindowsUsername("Shadab&<User>"))
        assertEquals("Admin2", WindowsCustomizationEngine.sanitizeWindowsUsername("  Admin2..  "))
    }

    @Test
    fun winUtilTweaksConfig_jsonRoundTripEnforcesMutualExclusions() {
        val raw = """
            {
              "disableWindowsUpdates": true,
              "securityOnlyUpdates": true,
              "disableIpv6": true,
              "preferIpv4OverIpv6": true,
              "removeEdge": true,
              "debloatEdge": true,
              "darkThemeDefault": true,
              "addUndoScript": true
            }
        """.trimIndent()
        val parsed = WinUtilTweaksConfig.fromJsonString(raw)
        assertTrue(parsed.disableWindowsUpdates)
        assertFalse(parsed.securityOnlyUpdates)
        assertTrue(parsed.disableIpv6)
        assertFalse(parsed.preferIpv4OverIpv6)
        assertTrue(parsed.removeEdge)
        assertFalse(parsed.debloatEdge)
        assertTrue(parsed.darkThemeDefault)
        assertTrue(parsed.addUndoScript)

        val reserialized = WinUtilTweaksConfig.fromJsonString(parsed.toJsonString())
        assertEquals(parsed.normalizedForComparison(), reserialized.normalizedForComparison())
    }

    @Test
    fun generateAutounattendXml_producesWellFormedXml() {
        val exp = WindowsExperienceConfig(
            bypassRequirements = true,
            bypassMicrosoftAccount = true,
            disableDataCollection = true,
            createLocalUser = true,
            localUsername = "Test&User",
            skipOobe = true
        )
        val xml = WindowsCustomizationEngine.generateAutounattendXml(
            experience = exp,
            winUtil = WinUtilTweaksConfig.standardPreset(),
            arch = "amd64",
            selectedEditionIndex = 6,
            hasSpecializeScript = true
        )
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        assertEquals("unattend", doc.documentElement.localName)
        assertTrue(xml.contains("<Value>6</Value>"))
        assertTrue(xml.contains("<Name>TestUser</Name>"))
    }

    @Test
    fun generateVentoySelfContainedXml_producesValidXmlWithChunkedGzipStagers() {
        val exp = WindowsExperienceConfig(bypassRequirements = true, bypassMicrosoftAccount = true)
        val wu = WinUtilTweaksConfig.standardPreset().copy(addUndoScript = true)
        val xml = WindowsCustomizationEngine.generateVentoySelfContainedXml(exp, wu, arch = "arm64")

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        assertEquals("unattend", doc.documentElement.localName)
        assertTrue(xml.contains("processorArchitecture=\"arm64\""))
        assertTrue(xml.contains("PocketBoot_Specialize.cmd.gzb64"))
        assertTrue(xml.contains("Undo_PocketBoot_Tweaks.cmd.gzb64"))
    }
}
