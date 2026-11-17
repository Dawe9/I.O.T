package com.example.id

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class CredentialType(val label: String) {
    CARD("Tarjeta"),
    TOKEN("Token")
}

enum class CredentialStatus(val label: String) {
    AUTHORIZED("Autorizada"),
    UNAUTHORIZED("No autorizada")
}

data class ManagedCredential(
    val id: Long,
    val uid: String,
    val type: CredentialType,
    val status: CredentialStatus
)

class CredentialRepository(context: Context) {
    private val database = CredentialDbHelper(context.applicationContext)

    fun getAll(): List<ManagedCredential> {
        val result = mutableListOf<ManagedCredential>()
        database.readableDatabase.query(
            CredentialDbHelper.TABLE,
            arrayOf(
                CredentialDbHelper.ID,
                CredentialDbHelper.UID,
                CredentialDbHelper.TYPE,
                CredentialDbHelper.STATUS
            ),
            null,
            null,
            null,
            null,
            "${CredentialDbHelper.ID} DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ManagedCredential(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(CredentialDbHelper.ID)),
                    uid = cursor.getString(cursor.getColumnIndexOrThrow(CredentialDbHelper.UID)),
                    type = CredentialType.valueOf(
                        cursor.getString(cursor.getColumnIndexOrThrow(CredentialDbHelper.TYPE))
                    ),
                    status = CredentialStatus.valueOf(
                        cursor.getString(cursor.getColumnIndexOrThrow(CredentialDbHelper.STATUS))
                    )
                )
            }
        }
        return result
    }

    fun add(uid: String, type: CredentialType, status: CredentialStatus): Boolean {
        val normalizedUid = uid.trim().uppercase()
        if (normalizedUid.isBlank()) return false

        val values = ContentValues().apply {
            put(CredentialDbHelper.UID, normalizedUid)
            put(CredentialDbHelper.TYPE, type.name)
            put(CredentialDbHelper.STATUS, status.name)
        }
        val id = database.writableDatabase.insertWithOnConflict(
            CredentialDbHelper.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return id != -1L
    }

    fun updateStatus(id: Long, status: CredentialStatus): Boolean {
        val values = ContentValues().apply {
            put(CredentialDbHelper.STATUS, status.name)
        }
        return database.writableDatabase.update(
            CredentialDbHelper.TABLE,
            values,
            "${CredentialDbHelper.ID} = ?",
            arrayOf(id.toString())
        ) == 1
    }

    fun delete(id: Long): Boolean =
        database.writableDatabase.delete(
            CredentialDbHelper.TABLE,
            "${CredentialDbHelper.ID} = ?",
            arrayOf(id.toString())
        ) == 1

    fun replaceAll(credentials: List<ManagedCredential>) {
        val db = database.writableDatabase
        val normalized = credentials.distinctBy {
            credentialKey(it.uid, it.type)
        }.associateBy { credentialKey(it.uid, it.type) }
        db.beginTransaction()
        try {
            val existing = mutableMapOf<String, MutableList<Long>>()
            db.query(
                CredentialDbHelper.TABLE,
                arrayOf(
                    CredentialDbHelper.ID,
                    CredentialDbHelper.UID,
                    CredentialDbHelper.TYPE
                ),
                null,
                null,
                null,
                null,
                "${CredentialDbHelper.ID} DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val uid = cursor.getString(cursor.getColumnIndexOrThrow(CredentialDbHelper.UID))
                    val type = CredentialType.valueOf(
                        cursor.getString(cursor.getColumnIndexOrThrow(CredentialDbHelper.TYPE))
                    )
                    existing.getOrPut(credentialKey(uid, type)) { mutableListOf() } +=
                        cursor.getLong(cursor.getColumnIndexOrThrow(CredentialDbHelper.ID))
                }
            }

            existing.forEach { (key, ids) ->
                if (key !in normalized) {
                    ids.forEach { id ->
                        db.delete(
                            CredentialDbHelper.TABLE,
                            "${CredentialDbHelper.ID} = ?",
                            arrayOf(id.toString())
                        )
                    }
                } else {
                    ids.drop(1).forEach { id ->
                        db.delete(
                            CredentialDbHelper.TABLE,
                            "${CredentialDbHelper.ID} = ?",
                            arrayOf(id.toString())
                        )
                    }
                }
            }
            normalized.values.forEach { credential ->
                val key = credentialKey(credential.uid, credential.type)
                val existingId = existing[key]?.firstOrNull()
                if (existingId != null) {
                    db.update(
                        CredentialDbHelper.TABLE,
                        ContentValues().apply {
                            put(CredentialDbHelper.STATUS, credential.status.name)
                        },
                        "${CredentialDbHelper.ID} = ?",
                        arrayOf(existingId.toString())
                    )
                } else {
                    db.insertOrThrow(
                        CredentialDbHelper.TABLE,
                        null,
                        ContentValues().apply {
                            put(CredentialDbHelper.UID, credential.uid.trim().uppercase())
                            put(CredentialDbHelper.TYPE, credential.type.name)
                            put(CredentialDbHelper.STATUS, credential.status.name)
                        }
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun credentialKey(uid: String, type: CredentialType): String =
        "${type.name}_${uid.trim().replace(":", "").replace("-", "").uppercase()}"
}

private class CredentialDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$UID TEXT NOT NULL, " +
                "$TYPE TEXT NOT NULL, " +
                "$STATUS TEXT NOT NULL, " +
                "UNIQUE($UID, $TYPE))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        private const val DATABASE_NAME = "id_credentials.db"
        private const val DATABASE_VERSION = 1
        const val TABLE = "managed_credentials"
        const val ID = "id"
        const val UID = "uid"
        const val TYPE = "type"
        const val STATUS = "status"
    }
}
