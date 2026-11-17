package com.example.id

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

internal fun managedCredentialDocumentId(uid: String, type: CredentialType): String {
    val compactUid = normalizeManagedCredentialUid(uid)
        .replace(":", "")
    return "${type.name}_$compactUid"
}

private fun normalizeManagedCredentialUid(uid: String): String {
    val compactUid = uid.trim()
        .uppercase()
        .replace(":", "")
        .replace("-", "")
    require(compactUid.matches(Regex("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})"))) {
        "El UID debe tener 4, 7 o 10 bytes hexadecimales."
    }
    return compactUid.chunked(2).joinToString(":")
}

class FirebaseCredentialRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val credentials = firestore.collection(CREDENTIALS_COLLECTION)

    suspend fun synchronize(localRepository: CredentialRepository): List<ManagedCredential> {
        val snapshot = credentials.get().await()
        val remoteCredentials = snapshot.documents.map { document ->
            ManagedCredential(
                id = 0,
                uid = document.getString("uid")
                    ?: error("La credencial ${document.id} no tiene UID."),
                type = CredentialType.valueOf(
                    document.getString("type")
                        ?: error("La credencial ${document.id} no tiene tipo.")
                ),
                status = CredentialStatus.valueOf(
                    document.getString("status")
                        ?: error("La credencial ${document.id} no tiene estado.")
                )
            )
        }

        if (snapshot.metadata.isFromCache) {
            if (remoteCredentials.isNotEmpty()) {
                localRepository.replaceAll(remoteCredentials)
            }
            return localRepository.getAll()
        }

        val localCredentials = localRepository.getAll()
        if (remoteCredentials.isEmpty() && localCredentials.isNotEmpty()) {
            val canonicalCredentials = localCredentials
                .map(::canonicalize)
                .distinctBy { managedCredentialDocumentId(it.uid, it.type) }
            saveAll(canonicalCredentials)
            localRepository.replaceAll(canonicalCredentials)
            return localRepository.getAll()
        }

        localRepository.replaceAll(remoteCredentials)
        return localRepository.getAll()
    }

    suspend fun save(credential: ManagedCredential) {
        val canonicalCredential = canonicalize(credential)
        credentials.document(
            managedCredentialDocumentId(canonicalCredential.uid, canonicalCredential.type)
        ).set(canonicalCredential.toFirestoreData()).await()
    }

    suspend fun delete(credential: ManagedCredential) {
        credentials.document(
            managedCredentialDocumentId(credential.uid, credential.type)
        ).delete().await()
    }

    private suspend fun saveAll(items: List<ManagedCredential>) {
        items.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { credential ->
                val canonicalCredential = canonicalize(credential)
                batch.set(
                    credentials.document(
                        managedCredentialDocumentId(
                            canonicalCredential.uid,
                            canonicalCredential.type
                        )
                    ),
                    canonicalCredential.toFirestoreData()
                )
            }
            batch.commit().await()
        }
    }

    private fun canonicalize(credential: ManagedCredential): ManagedCredential =
        credential.copy(uid = normalizeManagedCredentialUid(credential.uid))

    private fun ManagedCredential.toFirestoreData(): Map<String, String> =
        mapOf(
            "uid" to uid,
            "type" to type.name,
            "status" to status.name
        )

    private companion object {
        const val CREDENTIALS_COLLECTION = "managedCredentials"
        const val MAX_BATCH_SIZE = 400
    }
}
