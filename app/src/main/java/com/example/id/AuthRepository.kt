package com.example.id

import android.content.Context
import com.google.firebase.FirebaseException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

data class FirebaseUserProfile(
    val id: String,
    val email: String,
    val role: String,
    val cardUid: String
)

class AuthRepository(context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = context.applicationContext.getSharedPreferences(
        "firebase_session",
        Context.MODE_PRIVATE
    )

    suspend fun register(email: String, password: String) {
        val normalizedEmail = email.trim().lowercase()
        val user = auth.createUserWithEmailAndPassword(normalizedEmail, password)
            .await()
            .user ?: error("Firebase no devolvió el usuario creado.")
        try {
            createUserProfile(user)
        } catch (error: Exception) {
            auth.signOut()
            prefs.edit().remove("active_role").apply()
            throw error
        }
        prefs.edit().putString("active_role", "user").apply()
    }

    suspend fun login(email: String, password: String) {
        val normalizedEmail = email.trim().lowercase()
        val user = auth.signInWithEmailAndPassword(normalizedEmail, password)
            .await()
            .user ?: error("Firebase no devolvió el usuario autenticado.")
        try {
            refreshProfile(user)
        } catch (error: Exception) {
            auth.signOut()
            prefs.edit().remove("active_role").apply()
            throw error
        }
    }

    suspend fun refreshCurrentProfile(): FirebaseUserProfile? {
        val user = auth.currentUser ?: return null
        return refreshProfile(user)
    }

    suspend fun getCurrentProfile(): FirebaseUserProfile? {
        val user = auth.currentUser ?: return null
        return readProfile(user)
    }

    suspend fun listUsers(): List<FirebaseUserProfile> {
        return firestore.collection(USERS_COLLECTION)
            .orderBy("email")
            .get()
            .await()
            .documents
            .map { document ->
                FirebaseUserProfile(
                    id = document.id,
                    email = document.getString("email").orEmpty(),
                    role = document.getString("role") ?: "user",
                    cardUid = document.getString("cardUid").orEmpty()
                )
            }
    }

    suspend fun assignCardUid(userId: String, uid: String) {
        val normalizedUid = uid.trim().uppercase()
        require(normalizedUid.matches(Regex("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})"))) {
            "El UID debe tener 8, 14 o 20 caracteres hexadecimales."
        }
        firestore.collection(USERS_COLLECTION)
            .document(userId)
            .update(
                mapOf(
                    "cardUid" to normalizedUid,
                    "cardStatus" to "assigned",
                    "assignedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun submitTheftReport(): String {
        val user = auth.currentUser ?: error("Inicia sesión antes de reportar un robo.")
        val profile = readProfile(user)
        require(profile.role != ADMIN_ROLE) { "Solo las cuentas de usuario pueden reportar un robo." }
        require(profile.cardUid.isNotBlank()) {
            "Tu cuenta no tiene un UID asignado. Contacta al administrador."
        }
        val report = firestore.collection(REPORTS_COLLECTION)
            .add(
                mapOf(
                    "type" to "theft",
                    "uid" to profile.cardUid,
                    "userId" to user.uid,
                    "userEmail" to profile.email,
                    "status" to "pending",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
        return report.id
    }

    suspend fun registerNotificationToken() {
        val user = auth.currentUser ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .collection(DEVICES_COLLECTION)
            .document(token)
            .set(
                mapOf(
                    "token" to token,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun removeCardUid(uid: String) {
        val normalizedUid = uid.trim().uppercase()
        val users = firestore.collection(USERS_COLLECTION)
            .whereEqualTo("cardUid", normalizedUid)
            .get()
            .await()
        users.documents.forEach { user ->
            user.reference.update(
                mapOf(
                    "cardUid" to FieldValue.delete(),
                    "cardStatus" to FieldValue.delete(),
                    "assignedAt" to FieldValue.delete()
                )
            ).await()
        }
    }

    fun isAdmin(): Boolean = prefs.getString("active_role", "user") == "admin"

    fun isAuthenticated(): Boolean = auth.currentUser != null

    fun logout() {
        auth.signOut()
        prefs.edit().remove("active_role").apply()
    }

    suspend fun verifyCurrentPassword(password: String): Boolean {
        val user = auth.currentUser ?: return false
        val email = user.email ?: return false
        val credential = EmailAuthProvider.getCredential(email, password)
        user.reauthenticate(credential).await()
        return true
    }

    private suspend fun refreshProfile(user: FirebaseUser): FirebaseUserProfile {
        val reference = firestore.collection(USERS_COLLECTION).document(user.uid)
        val snapshot = reference.get().await()
        if (!snapshot.exists()) {
            createUserProfile(user)
        }
        return readProfile(user).also { profile ->
            prefs.edit()
                .putString("active_role", if (profile.role == ADMIN_ROLE) ADMIN_ROLE else "user")
                .apply()
        }
    }

    private suspend fun createUserProfile(user: FirebaseUser) {
        val email = user.email ?: error("La cuenta de Firebase no tiene correo.")
        firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .set(
                mapOf(
                    "email" to email,
                    "role" to "user",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    private suspend fun readProfile(user: FirebaseUser): FirebaseUserProfile {
        val snapshot = firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .get()
            .await()
        check(snapshot.exists()) { "No se encontró el perfil de usuario en Firestore." }
        return FirebaseUserProfile(
            id = user.uid,
            email = snapshot.getString("email") ?: user.email.orEmpty(),
            role = snapshot.getString("role") ?: "user",
            cardUid = snapshot.getString("cardUid").orEmpty()
        )
    }

    companion object {
        private const val USERS_COLLECTION = "users"
        private const val REPORTS_COLLECTION = "reports"
        private const val DEVICES_COLLECTION = "devices"
        private const val ADMIN_ROLE = "admin"
    }
}
