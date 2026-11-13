# ID Secure: control de acceso RFID con Android, Firebase y ESP32

Proyecto educativo que conecta una aplicacion Android con un lector RFID RC522
controlado por un ESP32. Permite iniciar sesion, asignar UID a usuarios,
consultar accesos y reportar el robo de una tarjeta.

## Contenido

- `app/`: aplicacion Android en Kotlin y Jetpack Compose.
- `hardware/firmware_rfid/`: firmware principal del ESP32.
- `hardware/lector_uid/`: sketch para leer el UID de una tarjeta por Serial.
- `hardware/fritzing_conexiones.csv`: conexiones entre ESP32, RC522, pantalla,
  LEDs y buzzer.
- `firestore.rules`: reglas de seguridad para perfiles, UID y reportes.
- `functions/`: Cloud Function para notificar a las cuentas administradoras.

## Requisitos

- Android Studio con soporte para Kotlin, Gradle y Jetpack Compose.
- JDK 25 (el wrapper de Gradle del proyecto configura esta version).
- Arduino IDE y la biblioteca `MFRC522`.
- Un ESP32 DevKit, RC522 y los componentes que aparecen en la tabla de
  conexiones.
- Un proyecto Firebase propio con Authentication (correo y contrasena) y
  Cloud Firestore habilitados.

## Configuracion de Firebase para Android

1. Crea un proyecto Firebase o usa uno al que tengas acceso.
2. Agrega una app Android con el application ID `com.example.id`.
3. Habilita **Authentication > Correo electronico/Contrasena** y crea
   **Cloud Firestore** en edicion Standard.
4. Descarga el archivo `google-services.json` de la configuracion de la app y
   colocalo en `app/google-services.json`. Ese archivo no se versiona porque
   contiene la configuracion del proyecto Firebase del propietario.
5. Publica el contenido de `firestore.rules` desde la consola de Firebase.
6. Abre el proyecto en Android Studio y ejecuta la configuracion Gradle y la
   aplicacion.
7. Registra la primera cuenta desde la app. En Firestore, cambia el campo
   `role` del documento `users/{UID}` de esa cuenta a `admin`. La aplicacion
   crea las cuentas nuevas con rol `user` y no permite elevarse a administrador.

## Configuracion y carga del ESP32

1. Abre `hardware/firmware_rfid/firmware_rfid.ino` en Arduino IDE.
2. Instala la biblioteca `MFRC522` y selecciona la placa ESP32 correspondiente.
3. Sustituye `TU_WIFI` y `TU_CLAVE_WIFI` en el sketch por la red Wi-Fi local.
4. Conecta el RC522 y los demas componentes segun
   `hardware/fritzing_conexiones.csv`.
5. Carga el sketch y revisa el monitor serie para obtener la direccion IP.
6. Inicia sesion en la app como administrador, abre **Dispositivo** y configura
   la direccion del ESP32.

La app comienza en modo de prueba. En ese modo no envia solicitudes al ESP32;
desactivalo solo cuando quieras probar el hardware. El ESP32 y los telefonos
deben estar conectados a una red local compatible.

## Reportes de robo y notificaciones push

La app guarda el reporte en Firestore. Para que llegue una notificacion push al
administrador y al tocarla se abra **Accesos** con el UID reportado, despliega la
Cloud Function incluida en `functions/`:

1. Instala Node.js 20 o posterior y Firebase CLI.
2. Ejecuta `npm install` desde `functions/`.
3. Ejecuta `firebase login` y selecciona tu propio proyecto con
   `firebase use --add`.
4. Ejecuta desde la raiz del proyecto:

   ```text
   firebase deploy --only functions,firestore:rules
   ```

El despliegue de Cloud Functions requiere que el proyecto Firebase tenga
habilitada la facturacion segun los requisitos actuales de Firebase. Tambien
debes permitir notificaciones en Android y abrir la app con la cuenta
administradora para registrar ese dispositivo.

## Funcionalidades principales

- Registro e inicio de sesion con Firebase Authentication.
- Perfiles, roles, UID asignados y reportes en Firestore.
- Administracion local de tarjetas y tokens desde la app.
- Consulta del UID asignado y del estado en el lector.
- Reporte de robo con notificacion push al administrador una vez desplegada la
  funcion.
- Doble confirmacion antes de bloquear o eliminar un UID.
- Modo de prueba que impide la comunicacion con el ESP32.

## Limites y seguridad

- La comunicacion local entre Android y ESP32 usa HTTP sin TLS. No expongas el
  dispositivo a Internet; utiliza una red privada.
- Los eventos del firmware se mantienen en memoria y pueden perderse al
  reiniciar el ESP32.
- La lista local de credenciales del administrador se guarda en cada telefono;
  no se sincroniza entre instalaciones.
- No subas `google-services.json`, `.firebaserc`, credenciales de Wi-Fi,
  archivos `.env` ni claves privadas al repositorio.
- Este prototipo es academico y no es una certificacion de ISO 27400 ni un
  sistema de control de acceso listo para produccion.
