// Control de acceso RFID con ESP32, lector RC522, LCD I2C y conexion Wi-Fi.
// Instala las librerias MFRC522 y LiquidCrystal_I2C desde el gestor del Arduino IDE.
//
// Conexiones para ESP32 DevKit V1 con modulo ESP-WROOM:
// RC522 VCC -> 3.3V
// RC522 GND -> GND
// RC522 SDA/SS -> GPIO 5
// RC522 SCK -> GPIO 18
// RC522 MOSI -> GPIO 23
// RC522 MISO -> GPIO 19
// RC522 RST -> GPIO 21
// RC522 IRQ -> no se usa
//
// En Arduino IDE selecciona la placa "ESP32 Dev Module".
// Si falla la carga, revisa el puerto y prueba mantener BOOT durante el inicio de la carga.

#include <SPI.h>
#include <MFRC522.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <time.h>

// Nombre y contrasena de una red Wi-Fi WPA2 a la que se conectan ambos telefonos.
// Sustituye los valores de ejemplo antes de cargar el programa.
const char* NOMBRE_WIFI = "TU_WIFI";
const char* CLAVE_WIFI = "TU_CLAVE_WIFI";

// Pines de seleccion y reinicio del lector RC522.
#define SS_PIN 5
#define RST_PIN 21
// LED que indica que el acceso fue autorizado.
#define LED_VERDE 13
// LED que indica que el acceso fue rechazado.
#define LED_ROJO 14
// Buzzer pasivo para emitir el resultado de la lectura.
#define BUZZER 27
// Pines I2C de la pantalla; se usa GPIO25/26 porque GPIO21 esta asignado al RST del RC522.
#define LCD_SDA 25
#define LCD_SCL 26

// Objeto que permite comunicarse con el lector RFID.
MFRC522 lectorRFID(SS_PIN, RST_PIN);
// Servidor HTTP para consultar el estado y cambiar los UID por Wi-Fi.
WebServer servidor(80);
// Almacenamiento persistente del ESP32 para conservar los UID tras reiniciar.
Preferences preferencias;
// Pantalla LCD I2C de 16 columnas y 2 filas; confirma la direccion I2C de tu modulo.
LiquidCrystal_I2C pantalla(0x27, 16, 2);
bool pantallaDisponible = false;
const byte MAX_EVENTOS = 20;
// Historial circular reciente para mostrar en el telefono sin esperar Firebase.
String historial[MAX_EVENTOS];
byte siguienteEvento = 0;
byte cantidadEventos = 0;
const unsigned int MAX_EVENTOS_PENDIENTES = 500;
const char* ARCHIVO_EVENTOS = "/eventos.log";
const char* ARCHIVO_EVENTOS_TEMP = "/eventos.tmp";
const char* ARCHIVO_EVENTOS_RESPALDO = "/eventos.bak";
bool almacenamientoEventosDisponible = false;
String idDispositivo;

// Resultado posible de la clasificacion de una credencial RFID.
enum TipoCredencial {
  TARJETA,
  TOKEN,
  DESCONOCIDA
};

// UID iniciales; al arrancar se reemplazan por los valores guardados, si existen.
String uidTarjetaPermitida = "25:0A:19:02";
String uidTokenBloqueado = "E1:13:FA:00";

String normalizarUID(String uid) {
  uid.trim();
  uid.toUpperCase();
  uid.replace(":", "");
  uid.replace("-", "");

  if (uid.length() != 8 && uid.length() != 14 && uid.length() != 20) {
    return "";
  }

  String normalizado;
  for (unsigned int indice = 0; indice < uid.length(); indice++) {
    char caracter = uid.charAt(indice);
    bool hexadecimal =
        (caracter >= '0' && caracter <= '9') ||
        (caracter >= 'A' && caracter <= 'F');
    if (!hexadecimal) {
      return "";
    }
    if (indice > 0 && indice % 2 == 0) {
      normalizado += ":";
    }
    normalizado += caracter;
  }
  return normalizado;
}

// Usa la hora de red si NTP ya sincronizo; informa si todavia no esta disponible.
String horaActual() {
  struct tm hora;
  if (!getLocalTime(&hora, 10)) {
    return "SIN_HORA_NTP";
  }

  char texto[20];
  strftime(texto, sizeof(texto), "%Y-%m-%d %H:%M:%S", &hora);
  return String(texto);
}

bool guardarEventoPendiente(const String& evento) {
  if (!almacenamientoEventosDisponible) {
    Serial.println("ERROR: no se pudo montar LittleFS; evento no persistido.");
    return false;
  }

  unsigned int cantidadPendiente = 0;
  if (LittleFS.exists(ARCHIVO_EVENTOS)) {
    File lectura = LittleFS.open(ARCHIVO_EVENTOS, FILE_READ);
    if (!lectura) {
      Serial.println("ERROR: no se pudo leer la cola persistente de eventos.");
      return false;
    }
    while (lectura.available()) {
      lectura.readStringUntil('\n');
      cantidadPendiente++;
    }
    lectura.close();
  }

  if (cantidadPendiente >= MAX_EVENTOS_PENDIENTES) {
    Serial.println("ERROR: cola persistente llena; sincroniza la app antes de registrar mas eventos.");
    return false;
  }

  uint32_t secuencia = preferencias.getUInt("eventSeq", 0) + 1;
  if (preferencias.putUInt("eventSeq", secuencia) == 0) {
    Serial.println("ERROR: no se pudo reservar el identificador persistente del evento.");
    return false;
  }

  String eventoId = idDispositivo + "-" + String(secuencia);
  File archivo = LittleFS.open(ARCHIVO_EVENTOS, FILE_APPEND);
  if (!archivo) {
    Serial.println("ERROR: no se pudo abrir la cola persistente de eventos.");
    return false;
  }
  size_t escrito = archivo.println(eventoId + "|" + evento);
  archivo.close();
  if (escrito == 0) {
    Serial.println("ERROR: no se pudo escribir el evento en LittleFS.");
    return false;
  }
  return true;
}

void prepararAlmacenamientoEventos() {
  bool yaInicializado = preferencias.getBool("eventsFsReady", false);
  bool montado = LittleFS.begin(false);
  if (!montado && !yaInicializado) {
    montado = LittleFS.begin(true);
  }
  if (!montado) {
    Serial.println("ERROR: no se pudo montar LittleFS; el historial no sera durable.");
    return;
  }
  if (!yaInicializado) {
    preferencias.putBool("eventsFsReady", true);
  }

  if (!LittleFS.exists(ARCHIVO_EVENTOS) && LittleFS.exists(ARCHIVO_EVENTOS_RESPALDO)) {
    LittleFS.rename(ARCHIVO_EVENTOS_RESPALDO, ARCHIVO_EVENTOS);
  } else if (LittleFS.exists(ARCHIVO_EVENTOS) && LittleFS.exists(ARCHIVO_EVENTOS_RESPALDO)) {
    LittleFS.remove(ARCHIVO_EVENTOS_RESPALDO);
  }
  if (LittleFS.exists(ARCHIVO_EVENTOS_TEMP)) {
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
  }

  idDispositivo = WiFi.macAddress();
  idDispositivo.replace(":", "");
  idDispositivo.toUpperCase();
  almacenamientoEventosDisponible = true;
}

// Inserta el evento mas reciente en RAM y lo conserva en LittleFS hasta sincronizarlo.
void registrarEvento(const String& uid, const String& estado, const String& tipo) {
  String evento = horaActual() + "|" + uid + "|" + estado + "|" + tipo;
  historial[siguienteEvento] = evento;
  siguienteEvento = (siguienteEvento + 1) % MAX_EVENTOS;
  if (cantidadEventos < MAX_EVENTOS) {
    cantidadEventos++;
  }
  guardarEventoPendiente(evento);
}

// Carga desde Preferences los UID configurados anteriormente.
void cargarConfiguracion() {
  // Abre el espacio "acceso" en modo lectura y escritura.
  preferencias.begin("acceso", false);
  // Usa los UID iniciales como valor predeterminado si no hay datos guardados.
  uidTarjetaPermitida = preferencias.getString("tarjeta", uidTarjetaPermitida);
  uidTokenBloqueado = preferencias.getString("token", uidTokenBloqueado);
}

// Actualiza y persiste un UID recibido como formulario POST en /configurar.
void configurarUID() {
  // Rechaza la solicitud si falta alguno de los parametros requeridos.
  if (!servidor.hasArg("tipo") || !servidor.hasArg("uid")) {
    servidor.send(400, "text/plain", "Envia tipo=tarjeta|token y uid=AA:BB:CC:DD");
    return;
  }

  String tipo = servidor.arg("tipo");
  String estado = servidor.arg("estado");
  if (estado.length() == 0) {
    estado = tipo == "tarjeta" ? "permitida" : "bloqueada";
  }
  String uid = normalizarUID(servidor.arg("uid"));
  if (uid.length() == 0) {
    servidor.send(400, "text/plain", "UID no valido. Usa 4, 7 o 10 bytes hexadecimales.");
    return;
  }

  // Autoriza una tarjeta o revoca exactamente la tarjeta actualmente configurada.
  if (tipo == "tarjeta") {
    if (estado == "permitida") {
      if (uid.equalsIgnoreCase(uidTokenBloqueado)) {
        uidTokenBloqueado = "";
        preferencias.putString("token", uidTokenBloqueado);
      }
      uidTarjetaPermitida = uid;
      preferencias.putString("tarjeta", uidTarjetaPermitida);
      servidor.send(200, "text/plain", "UID autorizado: " + uidTarjetaPermitida);
    } else if (estado == "bloqueada") {
      if (!uid.equalsIgnoreCase(uidTarjetaPermitida)) {
        servidor.send(409, "text/plain", "La tarjeta no es la autorizada actualmente");
        return;
      }
      uidTarjetaPermitida = "";
      preferencias.putString("tarjeta", uidTarjetaPermitida);
      servidor.send(200, "text/plain", "Acceso de la tarjeta revocado");
    } else {
      servidor.send(400, "text/plain", "El estado de tarjeta debe ser permitida o bloqueada");
    }
  // Configura el UID del token bloqueado.
  } else if (tipo == "token" && estado == "bloqueada") {
    if (uid.equalsIgnoreCase(uidTarjetaPermitida)) {
      servidor.send(409, "text/plain", "Ese UID ya esta configurado como tarjeta permitida");
      return;
    }
    uidTokenBloqueado = uid;
    preferencias.putString("token", uidTokenBloqueado);
    servidor.send(200, "text/plain", "UID de token bloqueado actualizado: " + uidTokenBloqueado);
  } else {
    servidor.send(400, "text/plain", "El tipo/estado debe ser tarjeta permitida/bloqueada o token bloqueada");
  }
}

// Convierte los bytes del UID a hexadecimal con dos puntos, por ejemplo AA:BB:CC:DD.
String uidComoTexto() {
  // Construye el texto del UID byte por byte.
  String uid;
  for (byte indice = 0; indice < lectorRFID.uid.size; indice++) {
    // Agrega un cero inicial cuando el byte ocupa un solo digito hexadecimal.
    if (lectorRFID.uid.uidByte[indice] < 0x10) {
      uid += "0";
    }
    // Anade el byte actual en formato hexadecimal y convierte el UID a mayusculas.
    uid += String(lectorRFID.uid.uidByte[indice], HEX);
    uid.toUpperCase();
    // Separa los bytes consecutivos con dos puntos.
    if (indice < lectorRFID.uid.size - 1) {
      uid += ":";
    }
  }
  return uid;
}

// Clasifica el UID como autorizado, bloqueado o no registrado.
TipoCredencial clasificarCredencial(const String& uid) {
  // Compara los UID sin distinguir entre mayusculas y minusculas.
  if (uid.equalsIgnoreCase(uidTarjetaPermitida)) {
    return TARJETA;
  }

  if (uid.equalsIgnoreCase(uidTokenBloqueado)) {
    return TOKEN;
  }

  return DESCONOCIDA;
}

// Devuelve true unicamente para el UID de tarjeta autorizado.
bool accesoPermitido(const String& uid) {
  // Solo la clasificacion TARJETA concede acceso.
  return clasificarCredencial(uid) == TARJETA;
}

// Confirma que el ESP32 responde; no expone los UID configurados.
void responderEstado() {
  servidor.send(200, "text/plain", "ESP32 RFID conectado");
}

// Devuelve primero el evento mas reciente con campos separados por "|".
void responderEventos() {
  String respuesta;
  for (byte indice = 0; indice < cantidadEventos; indice++) {
    int posicion = (siguienteEvento + MAX_EVENTOS - 1 - indice) % MAX_EVENTOS;
    respuesta += historial[posicion] + "\n";
  }
  servidor.send(200, "text/plain; charset=utf-8", respuesta);
}

bool secuenciaDeEvento(const String& linea, uint32_t& secuencia) {
  int separador = linea.indexOf('|');
  if (separador <= 0) {
    return false;
  }

  String prefijo = idDispositivo + "-";
  String eventoId = linea.substring(0, separador);
  if (!eventoId.startsWith(prefijo)) {
    return false;
  }

  String textoSecuencia = eventoId.substring(prefijo.length());
  if (textoSecuencia.isEmpty()) {
    return false;
  }
  for (unsigned int indice = 0; indice < textoSecuencia.length(); indice++) {
    if (!isDigit(textoSecuencia.charAt(indice))) {
      return false;
    }
  }

  secuencia = static_cast<uint32_t>(textoSecuencia.toInt());
  return secuencia > 0;
}

void responderEventosPendientes() {
  if (!almacenamientoEventosDisponible) {
    servidor.send(503, "text/plain", "El almacenamiento persistente no esta disponible");
    return;
  }
  if (!LittleFS.exists(ARCHIVO_EVENTOS)) {
    servidor.send(200, "text/plain; charset=utf-8", "");
    return;
  }

  File archivo = LittleFS.open(ARCHIVO_EVENTOS, FILE_READ);
  if (!archivo) {
    servidor.send(500, "text/plain", "No se pudo leer el historial pendiente");
    return;
  }
  servidor.streamFile(archivo, "text/plain; charset=utf-8");
  archivo.close();
}

bool confirmarEventosHasta(uint32_t hasta) {
  if (!LittleFS.exists(ARCHIVO_EVENTOS)) {
    return true;
  }

  File entrada = LittleFS.open(ARCHIVO_EVENTOS, FILE_READ);
  File temporal = LittleFS.open(ARCHIVO_EVENTOS_TEMP, FILE_WRITE);
  if (!entrada || !temporal) {
    if (entrada) entrada.close();
    if (temporal) temporal.close();
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
    return false;
  }

  bool huboEventosConfirmados = false;
  bool escrituraCorrecta = true;
  while (entrada.available()) {
    String linea = entrada.readStringUntil('\n');
    linea.trim();
    if (linea.isEmpty()) {
      continue;
    }

    uint32_t secuencia = 0;
    if (secuenciaDeEvento(linea, secuencia) && secuencia <= hasta) {
      huboEventosConfirmados = true;
      continue;
    }
    if (temporal.println(linea) == 0) {
      escrituraCorrecta = false;
      break;
    }
  }
  entrada.close();
  temporal.close();

  if (!escrituraCorrecta) {
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
    return false;
  }
  if (!huboEventosConfirmados) {
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
    return true;
  }

  if (LittleFS.exists(ARCHIVO_EVENTOS_RESPALDO)) {
    LittleFS.remove(ARCHIVO_EVENTOS_RESPALDO);
  }
  if (!LittleFS.rename(ARCHIVO_EVENTOS, ARCHIVO_EVENTOS_RESPALDO)) {
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
    return false;
  }
  if (!LittleFS.rename(ARCHIVO_EVENTOS_TEMP, ARCHIVO_EVENTOS)) {
    LittleFS.rename(ARCHIVO_EVENTOS_RESPALDO, ARCHIVO_EVENTOS);
    LittleFS.remove(ARCHIVO_EVENTOS_TEMP);
    return false;
  }
  LittleFS.remove(ARCHIVO_EVENTOS_RESPALDO);
  return true;
}

void confirmarEventos() {
  if (!almacenamientoEventosDisponible) {
    servidor.send(503, "text/plain", "El almacenamiento persistente no esta disponible");
    return;
  }
  if (!servidor.hasArg("hasta")) {
    servidor.send(400, "text/plain", "Falta el identificador de secuencia hasta");
    return;
  }

  String textoSecuencia = servidor.arg("hasta");
  if (textoSecuencia.isEmpty()) {
    servidor.send(400, "text/plain", "La secuencia no es valida");
    return;
  }
  for (unsigned int indice = 0; indice < textoSecuencia.length(); indice++) {
    if (!isDigit(textoSecuencia.charAt(indice))) {
      servidor.send(400, "text/plain", "La secuencia no es valida");
      return;
    }
  }

  uint32_t hasta = static_cast<uint32_t>(textoSecuencia.toInt());
  if (hasta == 0 || !confirmarEventosHasta(hasta)) {
    servidor.send(500, "text/plain", "No se pudo confirmar el historial sincronizado");
    return;
  }
  servidor.send(200, "text/plain", "Eventos confirmados");
}

// Responde la consulta de acceso para el UID enviado por la aplicacion.
void verificarUID() {
  if (!servidor.hasArg("uid")) {
    servidor.send(400, "text/plain", "Falta el UID");
    return;
  }

  String uid = normalizarUID(servidor.arg("uid"));
  if (uid.length() == 0) {
    servidor.send(400, "text/plain", "UID no valido");
    return;
  }

  servidor.send(
      200,
      "text/plain",
      accesoPermitido(uid) ? "AUTORIZADA" : "NO AUTORIZADA");
}

// Registra una alerta recibida y activa los indicadores locales del dispositivo.
void recibirAlerta() {
  String tipo = servidor.arg("tipo");
  if (tipo != "robo" && tipo != "seguridad") {
    servidor.send(400, "text/plain", "El tipo debe ser robo o seguridad");
    return;
  }

  String estado = tipo == "robo" ? "REPORTE_ROBO" : "AVISO_SEGURIDAD";
  registrarEvento("-", estado, "ALERTA");
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_ROJO, HIGH);
  sonidoDenegado();
  mostrarPantalla("AVISO SEGURIDAD", "Revisar app");
  servidor.send(201, "text/plain", "Alerta registrada en el ESP32");
}

void apagarLeds() {
  // Apaga ambos indicadores para dejar el dispositivo en reposo.
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_ROJO, LOW);
}

void mostrarPantalla(const String& linea1, const String& linea2) {
  if (!pantallaDisponible) {
    return;
  }

  // Limita cada mensaje a los 16 caracteres que caben en una fila.
  pantalla.clear();
  pantalla.setCursor(0, 0);
  pantalla.print(linea1.substring(0, 16));
  pantalla.setCursor(0, 1);
  pantalla.print(linea2.substring(0, 16));
}

// Busca dispositivos I2C y prueba la pantalla si encuentra la direccion configurada.
void diagnosticarLCD() {
  Serial.println("--- Diagnostico LCD I2C ---");
  bool dispositivoEncontrado = false;
  bool direccionLCDEncontrada = false;

  for (byte direccion = 1; direccion < 127; direccion++) {
    Wire.beginTransmission(direccion);
    if (Wire.endTransmission() == 0) {
      Serial.print("Dispositivo I2C detectado en 0x");
      Serial.println(direccion, HEX);
      dispositivoEncontrado = true;
      if (direccion == 0x27) {
        direccionLCDEncontrada = true;
      }
    }
  }

  if (!dispositivoEncontrado) {
    Serial.println("ERROR: no se detectan dispositivos I2C. Revisa VCC, GND, SDA y SCL.");
  } else if (!direccionLCDEncontrada) {
    Serial.println("ERROR: no se detecto la LCD en 0x27. Ajusta la direccion del constructor.");
  } else {
    pantallaDisponible = true;
    pantalla.init();
    pantalla.backlight();
    mostrarPantalla("Prueba LCD", "I2C detectada");
    Serial.println("OK: LCD detectada en 0x27; se envio un mensaje de prueba.");
  }
  Serial.println("---------------------------");
}

// Emite dos tonos de prueba; el sonido solo puede confirmarse fisicamente.
void diagnosticarBuzzer() {
  Serial.println("--- Diagnostico buzzer ---");
  Serial.println("Enviando dos tonos de prueba al GPIO27.");
  tone(BUZZER, 2200, 180);
  delay(250);
  tone(BUZZER, 500, 180);
  delay(250);
  noTone(BUZZER);
  Serial.println("OK: tonos enviados. Si no se oyen, revisa buzzer, polaridad y GND.");
  Serial.println("--------------------------");
}

void sonidoPermitido() {
  tone(BUZZER, 2200, 180);
}

void sonidoDenegado() {
  // Emite dos tonos graves para indicar que se rechazo la credencial.
  tone(BUZZER, 500, 180);
  delay(220);
  tone(BUZZER, 500, 180);
}

void indicarResultado(bool accesoPermitido) {
  // Enciende un solo LED segun el resultado de la validacion.
  if (accesoPermitido) {
    digitalWrite(LED_VERDE, HIGH);
    digitalWrite(LED_ROJO, LOW);
  } else {
    digitalWrite(LED_VERDE, LOW);
    digitalWrite(LED_ROJO, HIGH);
  }
}

// Consulta el registro de version para comprobar si el RC522 responde por SPI.
void diagnosticarRC522() {
  // Imprime el cableado esperado y lee el registro de version del lector.
  Serial.println("--- Diagnostico de pines RC522 ---");
  Serial.println("SS/SDA=GPIO5, SCK=GPIO18, MOSI=GPIO23, MISO=GPIO19, RST=GPIO21");

  byte version = lectorRFID.PCD_ReadRegister(MFRC522::VersionReg);
  Serial.print("Version MFRC522: 0x");
  Serial.println(version, HEX);

  // 0x00 o 0xFF suelen indicar falta de comunicacion o alimentacion.
  if (version == 0x00 || version == 0xFF) {
    Serial.println("ERROR RC522: no hay respuesta por SPI.");
    Serial.println("Revisa VCC=3.3V, GND y los cinco cables SPI/RST.");
  } else {
    Serial.println("OK RC522: SPI y alimentacion responden.");
    Serial.println("Si no aparece el UID, acerca un token RFID de 13.56 MHz.");
  }
  Serial.println("---------------------------------");
}

// Se conecta a la red Wi-Fi compartida e inicia las rutas consultadas por la app.
void conectarWiFi() {
  // Usa una red Wi-Fi existente; el ESP32 no crea un punto de acceso.
  WiFi.mode(WIFI_STA);
  prepararAlmacenamientoEventos();
  WiFi.begin(NOMBRE_WIFI, CLAVE_WIFI);
  Serial.print("Conectando a WiFi");

  // Espera hasta 15 segundos a que se establezca la conexion.
  unsigned long inicio = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - inicio < 15000) {
    delay(500);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println();
    Serial.print("IP del dispositivo: ");
    Serial.println(WiFi.localIP());
    configTzTime("UTC0", "pool.ntp.org", "time.nist.gov");
    // Las consultas son GET; los cambios de acceso y las alertas usan POST.
    servidor.on("/", responderEstado); // Estado en la ruta principal.
    servidor.on("/estado", responderEstado); // Estado en una ruta explicita.
    servidor.on("/configurar", HTTP_POST, configurarUID); // Actualizacion de un UID.
    servidor.on("/verificar", HTTP_GET, verificarUID);
    servidor.on("/eventos", HTTP_GET, responderEventos);
    servidor.on("/eventosPendientes", HTTP_GET, responderEventosPendientes);
    servidor.on("/eventos/confirmar", HTTP_POST, confirmarEventos);
    servidor.on("/alerta", HTTP_POST, recibirAlerta);
    servidor.begin();
    Serial.println("Servidor WiFi iniciado");
  } else {
    Serial.println("\nNo se pudo conectar a WiFi; el lector RFID seguira funcionando localmente");
    // Imprime el estado de Wi-Fi para ayudar a diagnosticar el fallo.
    Serial.print("Estado WiFi: ");
    wl_status_t estado = WiFi.status();
    Serial.println(estado);
    if (estado == WL_NO_SSID_AVAIL) {
      Serial.println("La red no esta visible para la ESP32.");
    } else if (estado == WL_CONNECT_FAILED) {
      Serial.println("La autenticacion fallo: revisa la contrasena o usa WPA2.");
    } else if (estado == WL_DISCONNECTED) {
      Serial.println("La ESP32 esta desconectada; revisa que la red sea 2.4 GHz.");
    }
  }
}

void setup() {
  // Configura los LED y el buzzer como salidas.
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_ROJO, OUTPUT);
  pinMode(BUZZER, OUTPUT);
  apagarLeds();

  // Inicia Serial para mostrar diagnosticos, estado de Wi-Fi y UID leidos.
  Serial.begin(115200);
  Wire.begin(LCD_SDA, LCD_SCL);
  diagnosticarLCD();
  diagnosticarBuzzer();
  // Inicializa SPI con el orden de pines SCK, MISO, MOSI y SS.
  SPI.begin(18, 19, 23, 5); // SCK, MISO, MOSI, SS
  // Inicializa el lector RFID.
  lectorRFID.PCD_Init();
  diagnosticarRC522();
  // Restaura la configuracion persistente e intenta conectar a Wi-Fi.
  cargarConfiguracion();
  conectarWiFi();

  mostrarPantalla("Control RFID", "Acerque tarjeta");
  Serial.println("Acerque una tarjeta o token RFID...");
}

void loop() {
  // Procesa solicitudes HTTP mientras el ESP32 siga conectado al Wi-Fi.
  if (WiFi.status() == WL_CONNECTED) {
    servidor.handleClient();
  }

  // Espera una credencial nueva; vuelve al inicio del ciclo si no hay ninguna.
  if (!lectorRFID.PICC_IsNewCardPresent()) {
    return;
  }

  // Lee el numero de serie (UID) de la credencial detectada.
  if (!lectorRFID.PICC_ReadCardSerial()) {
    return;
  }

  // Convierte y clasifica el UID, y lo imprime en el monitor serie.
  String uid = uidComoTexto();
  TipoCredencial tipo = clasificarCredencial(uid);
  bool permitido = accesoPermitido(uid);
  registrarEvento(uid, permitido ? "AUTORIZADO" : "DENEGADO", "ACCESO");
  Serial.print("UID: ");
  Serial.println(uid);

  // Solo se autoriza la tarjeta registrada; los otros UID se rechazan.
  if (permitido) {
    Serial.println("ACCESO PERMITIDO: tarjeta autorizada");
    indicarResultado(true);
    mostrarPantalla("ACCESO PERMITIDO", "Tarjeta valida");
    sonidoPermitido();
  } else if (tipo == TOKEN) {
    Serial.println("ACCESO DENEGADO: token bloqueado");
    indicarResultado(false);
    mostrarPantalla("ACCESO DENEGADO", "Token bloqueado");
    sonidoDenegado();
  } else {
    Serial.println("ACCESO DENEGADO: credencial no registrada");
    indicarResultado(false);
    mostrarPantalla("ACCESO DENEGADO", "No registrada");
    sonidoDenegado();
  }

  // Cierra la comunicacion con la credencial antes de esperar otra lectura.
  lectorRFID.PICC_HaltA();
  lectorRFID.PCD_StopCrypto1();
  Serial.println("Retire la tarjeta o token...");
  delay(1000);
  apagarLeds();
  mostrarPantalla("Control RFID", "Acerque tarjeta");
  Serial.println("Acerque otra tarjeta o token RFID...");
}
