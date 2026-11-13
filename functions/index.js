const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const logger = require("firebase-functions/logger");

initializeApp();

exports.notifyAdminsOfTheftReport = onDocumentCreated(
  "reports/{reportId}",
  async (event) => {
    const report = event.data && event.data.data();
    if (
      !report ||
      report.type !== "theft" ||
      report.status !== "pending" ||
      typeof report.uid !== "string" ||
      typeof report.userId !== "string"
    ) {
      logger.error("Invalid theft report; no notification sent.", {
        reportId: event.params.reportId,
      });
      return;
    }

    const db = getFirestore();
    const userSnapshot = await db.collection("users").doc(report.userId).get();
    if (
      !userSnapshot.exists ||
      userSnapshot.get("role") !== "user" ||
      userSnapshot.get("cardUid") !== report.uid
    ) {
      logger.error("Theft report does not match the user's assigned card.", {
        reportId: event.params.reportId,
      });
      return;
    }

    const admins = await db.collection("users").where("role", "==", "admin").get();
    const tokenSnapshots = await Promise.all(
      admins.docs.map((admin) => admin.ref.collection("devices").get()),
    );
    const destinations = tokenSnapshots.flatMap((snapshot) =>
      snapshot.docs.map((device) => ({
        token: device.get("token"),
        reference: device.ref,
      })),
    ).filter((device) => typeof device.token === "string" && device.token.length > 0);

    if (destinations.length === 0) {
      logger.warn("No administrator device tokens registered.", {
        reportId: event.params.reportId,
      });
      return;
    }

    for (let offset = 0; offset < destinations.length; offset += 500) {
      const batch = destinations.slice(offset, offset + 500);
      const response = await getMessaging().sendEachForMulticast({
        tokens: batch.map((device) => device.token),
        notification: {
          title: "Reporte de robo",
          body: "Un usuario reportó el robo de una tarjeta. Toca para revisar y bloquear el UID.",
        },
        data: {
          uid: report.uid,
          reportId: event.params.reportId,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "theft_reports",
          },
        },
      });

      const invalidTokens = [];
      response.responses.forEach((result, index) => {
        if (
          !result.success &&
          result.error &&
          [
            "messaging/invalid-registration-token",
            "messaging/registration-token-not-registered",
          ].includes(result.error.code)
        ) {
          invalidTokens.push(batch[index].reference.delete());
        }
      });
      await Promise.all(invalidTokens);
      logger.info("Admin theft report notifications sent.", {
        reportId: event.params.reportId,
        successes: response.successCount,
        failures: response.failureCount,
      });
    }
  },
);
