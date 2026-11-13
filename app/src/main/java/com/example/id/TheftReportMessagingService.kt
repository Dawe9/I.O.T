package com.example.id

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TheftReportMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)
            .collection("devices")
            .document(token)
            .set(
                mapOf(
                    "token" to token,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
            .addOnFailureListener { error ->
                Log.e("TheftReportMessaging", "Could not register refreshed FCM token.", error)
            }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val uid = message.data["uid"] ?: return
        showTheftReportNotification(
            context = this,
            uid = uid,
            reportId = message.data["reportId"].orEmpty(),
            title = message.notification?.title ?: "Reporte de robo",
            body = message.notification?.body ?: "Un usuario reportó el robo de su tarjeta."
        )
    }
}

internal fun ensureTheftNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(THEFT_NOTIFICATION_CHANNEL) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                THEFT_NOTIFICATION_CHANNEL,
                "Reportes de robo",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de robo que requieren revisar y bloquear una tarjeta."
            }
        )
    }
}

private fun showTheftReportNotification(
    context: Context,
    uid: String,
    reportId: String,
    title: String,
    body: String
) {
    ensureTheftNotificationChannel(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_THEFT_UID, uid)
        putExtra(MainActivity.EXTRA_THEFT_REPORT_ID, reportId)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        reportId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, THEFT_NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    NotificationManagerCompat.from(context).notify(reportId.hashCode(), notification)
}

private const val THEFT_NOTIFICATION_CHANNEL = "theft_reports"
