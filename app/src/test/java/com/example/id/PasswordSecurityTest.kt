package com.example.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordSecurityTest {
    @Test
    fun passwordHash_isStableAndVerifiable() {
        val salt = PasswordSecurity.generateSalt()
        val hash = PasswordSecurity.hashPassword("SecurePass123", salt)
        assertTrue(PasswordSecurity.verifyPassword("SecurePass123", salt, hash))
    }

    @Test
    fun generatedSalt_isValidBase64() {
        val salt = PasswordSecurity.generateSalt()
        assertEquals(24, salt.length)
    }
}
