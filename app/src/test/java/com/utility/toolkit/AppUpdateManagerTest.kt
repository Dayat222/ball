package com.utility.toolkit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * JVM test for the OTA checksum bit. Uses the standard "abc" SHA-256 vector so
 * the hex encoding (including the sign-extension fix) is checked against a
 * known good value with no Android dependency.
 */
class AppUpdateManagerTest {

    @Test
    fun sha256HexMatchesKnownVector() {
        val file = File.createTempFile("toolkit-sha", ".bin").apply {
            writeText("abc")
            deleteOnExit()
        }

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AppUpdateManager.sha256Hex(file)
        )
    }
}