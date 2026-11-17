package com.example.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun parsePendingAccessEvents_readsDurableEventIds() {
        val events = parsePendingAccessEvents(
            "AABBCCDDEEFF-12|2026-11-12 18:20:30|AA:BB:CC:DD|AUTORIZADO|ACCESO\r\n"
        )

        assertEquals(1, events.size)
        assertEquals("AABBCCDDEEFF-12", events.single().eventId)
        assertEquals("AA:BB:CC:DD", events.single().uid)
        assertEquals("ACCESO", events.single().type)
    }

    @Test
    fun parsePendingAccessEvents_rejectsMalformedLines() {
        assertThrows(IOException::class.java) {
            parsePendingAccessEvents("2026-11-12|AA:BB:CC:DD|AUTORIZADO|ACCESO")
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

    @Test
    fun unregisteredDeniedAccess_isOfferedForManagement() {
        val event = AccessEvent("2026-11-12 18:20:30", "AA:BB:CC:DD", "DENEGADO", "ACCESO")

        assertTrue(isUnregisteredDeniedEvent(event, emptyList()))
    }

    @Test
    fun registeredUid_isNotOfferedAgainRegardlessOfSeparators() {
        val event = AccessEvent("2026-11-12 18:20:30", "AA:BB:CC:DD", "DENEGADO", "ACCESO")
        val credentials = listOf(
            ManagedCredential(
                id = 1,
                uid = "AABBCCDD",
                type = CredentialType.CARD,
                status = CredentialStatus.AUTHORIZED
            )
        )

        assertFalse(isUnregisteredDeniedEvent(event, credentials))
    }

    @Test
    fun nonDeniedAccessAndAlertEvents_areNotOffered() {
        val authorizedEvent =
            AccessEvent("2026-11-12 18:20:30", "AA:BB:CC:DD", "AUTORIZADO", "ACCESO")
        val alertEvent =
            AccessEvent("2026-11-12 18:21:00", "AA:BB:CC:DD", "DENEGADO", "ALERTA")

        assertFalse(isUnregisteredDeniedEvent(authorizedEvent, emptyList()))
        assertFalse(isUnregisteredDeniedEvent(alertEvent, emptyList()))
    }
}
