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
