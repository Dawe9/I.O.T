package com.example.id

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AccessEvent(
    val timestamp: String,
    val uid: String,
    val status: String,
    val type: String
)

// Evita iniciar una solicitud si el modo de prueba esta activo, incluso si cambia
// mientras la tarea de red espera a ejecutarse.
internal fun <T> runEsp32OnlyOutsideTestMode(
    isTestMode: () -> Boolean,
    action: () -> T
): T {
    check(!isTestMode()) { "La conexión con el ESP32 está desactivada en modo de prueba." }
    return action()
}

internal fun parseAccessEvents(response: String): List<AccessEvent> =
    response.lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split('|')
            if (fields.size != 4) {
                throw IOException("El ESP32 devolvió un evento con formato inválido")
            }
            AccessEvent(fields[0], fields[1], fields[2], fields[3])
        }
        .toList()

/**
 * Cliente HTTP del ESP32 para consultar estado, UID, eventos y enviar configuraciones o alertas.
 * Ejecuta cada solicitud en un hilo de trabajo, no en el hilo principal de Android.
 */
class Esp32RfidClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000
) {
    // Quita la barra final para unir la dirección con las rutas sin duplicar "/".
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        // Rechaza direcciones incompletas y tiempos de espera no válidos al crear el cliente.
        require(this.baseUrl.startsWith("http://") || this.baseUrl.startsWith("https://")) {
            "La dirección del ESP32 debe comenzar con http:// o https://"
        }
        require(connectTimeoutMillis > 0) { "El tiempo de conexión debe ser mayor que cero" }
        require(readTimeoutMillis > 0) { "El tiempo de lectura debe ser mayor que cero" }
    }

    /** Comprueba que el firmware responde en GET /estado. */
    fun getStatus(): String = get("/estado")

    /** Lee los eventos recientes compartidos que el ESP32 conserva mientras esta encendido. */
    fun getEvents(): List<AccessEvent> = parseAccessEvents(get("/eventos"))

    /** Consulta en GET /verificar si un UID tiene acceso autorizado. */
    fun checkUid(uid: String): String =
        get("/verificar?uid=${encodeQueryValue(uid)}")

    /** Envía por POST /configurar el UID que quedara autorizado como tarjeta. */
    fun setAllowedCardUid(uid: String): String =
        postForm(
            "/configurar",
            mapOf("tipo" to "tarjeta", "estado" to "permitida", "uid" to uid)
        )

    /** Revoca por POST /configurar la tarjeta autorizada, si su UID coincide. */
    fun setBlockedCardUid(uid: String): String =
        postForm(
            "/configurar",
            mapOf("tipo" to "tarjeta", "estado" to "bloqueada", "uid" to uid)
        )

    /** Envía por POST /configurar el UID del token bloqueado. */
    fun setBlockedTokenUid(uid: String): String =
        postForm(
            "/configurar",
            mapOf("tipo" to "token", "estado" to "bloqueada", "uid" to uid)
        )

    /** Registra por POST /alerta un reporte de robo o un aviso de seguridad. */
    fun reportAlert(type: String): String =
        postForm("/alerta", mapOf("tipo" to type))

    private fun get(pathAndQuery: String): String = request("GET", pathAndQuery)

    private fun postForm(path: String, fields: Map<String, String>): String {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encodeQueryValue(key)}=${encodeQueryValue(value)}"
        }
        return request("POST", path, body)
    }

    // Ejecuta GET o POST como formulario y lanza IOException ante una respuesta HTTP fallida.
    private fun request(method: String, pathAndQuery: String, body: String? = null): String {
        val connection = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val responseStream =
                if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (statusCode !in 200..299) {
                val details = if (responseBody.isBlank()) "" else ": $responseBody"
                throw IOException("El ESP32 respondió HTTP $statusCode$details")
            }

            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    // Codifica valores para la query string o el formulario POST.
    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
