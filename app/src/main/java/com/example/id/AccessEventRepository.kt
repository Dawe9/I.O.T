package com.example.id

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

class AccessEventRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val events = firestore.collection(EVENTS_COLLECTION)

    suspend fun savePendingEvents(pendingEvents: List<AccessEvent>) {
        pendingEvents.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { event ->
                require(event.eventId.isNotBlank()) {
                    "El ESP32 devolvió un evento sin identificador persistente."
                }
                batch.set(
                    events.document(event.eventId),
                    mapOf(
                        "eventId" to event.eventId,
                        "timestamp" to event.timestamp,
                        "uid" to event.uid,
                        "status" to event.status,
                        "type" to event.type
                    )
                )
            }
            batch.commit().await()
        }
    }

    suspend fun getRecentEvents(): List<AccessEvent> =
        events.orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MAX_HISTORY_SIZE)
            .get()
            .await()
            .documents
            .map { document ->
                AccessEvent(
                    timestamp = document.getString("timestamp")
                        ?: error("El evento ${document.id} no tiene hora."),
                    uid = document.getString("uid")
                        ?: error("El evento ${document.id} no tiene UID."),
                    status = document.getString("status")
                        ?: error("El evento ${document.id} no tiene estado."),
                    type = document.getString("type")
                        ?: error("El evento ${document.id} no tiene tipo."),
                    eventId = document.id
                )
            }

    private companion object {
        const val EVENTS_COLLECTION = "accessEvents"
        const val MAX_BATCH_SIZE = 400
        const val MAX_HISTORY_SIZE = 200L
    }
}
