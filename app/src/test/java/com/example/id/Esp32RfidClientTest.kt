package com.example.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class Esp32RfidClientTest {
    @Test
    fun parseAccessEvents_readsSharedAccessAndAlertLines() {
        val events = parseAccessEvents(
            "2026-11-12 18:20:30|AA:BB:CC:DD|AUTORIZADO|ACCESO\n" +
                "2026-11-12 18:21:00|-|REPORTE_ROBO|ALERTA\n"
        )

        assertEquals(2, events.size)
        assertEquals("AA:BB:CC:DD", events[0].uid)
        assertEquals("AUTORIZADO", events[0].status)
        assertEquals("ALERTA", events[1].type)
        assertEquals("REPORTE_ROBO", events[1].status)
    }

    @Test
    fun parseAccessEvents_rejectsMalformedLines() {
        assertThrows(IOException::class.java) {
            parseAccessEvents("2026-11-12|AA:BB:CC:DD|AUTORIZADO")
        }
    }

    @Test
    fun testMode_blocksEsp32Interaction() {
        var interactionStarted = false

        assertThrows(IllegalStateException::class.java) {
            runEsp32OnlyOutsideTestMode(isTestMode = { true }) {
                interactionStarted = true
            }
        }

        assertFalse(interactionStarted)
    }

    @Test
    fun liveMode_allowsEsp32Interaction() {
        val result = runEsp32OnlyOutsideTestMode(isTestMode = { false }) { "connected" }

        assertEquals("connected", result)
    }
}
