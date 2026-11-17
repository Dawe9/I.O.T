package com.example.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FirebaseCredentialRepositoryTest {
    @Test
    fun managedCredentialDocumentId_ignoresUidSeparators() {
        assertEquals(
            managedCredentialDocumentId("AA:BB:CC:DD", CredentialType.CARD),
            managedCredentialDocumentId("AABB-CCDD", CredentialType.CARD)
        )
    }

    @Test
    fun managedCredentialDocumentId_keepsCredentialTypesSeparate() {
        assertNotEquals(
            managedCredentialDocumentId("AA:BB:CC:DD", CredentialType.CARD),
            managedCredentialDocumentId("AA:BB:CC:DD", CredentialType.TOKEN)
        )
    }
}
