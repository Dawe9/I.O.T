// Programa de prueba para leer y mostrar por Serial el UID de tarjetas y tokens.
// Requiere un ESP32 DevKit V1, un RC522 y la libreria MFRC522 instalada en Arduino IDE.
//
// Conexiones del RC522 al ESP32:
// RC522 VCC -> 3.3V
// RC522 GND -> GND
// RC522 SDA/SS -> GPIO 5
// RC522 SCK -> GPIO 18
// RC522 MOSI -> GPIO 23
// RC522 MISO -> GPIO 19
// RC522 RST -> GPIO 21
// RC522 IRQ -> no se usa

#include <SPI.h>
#include <MFRC522.h>

#define SS_PIN 5
#define RST_PIN 21

// Crea el lector RC522 usando los pines SS y RST indicados arriba.
MFRC522 lectorRFID(SS_PIN, RST_PIN);

// Convierte los bytes del UID a hexadecimal separado por dos puntos.
String uidComoTexto() {
  // Construye el UID como una cadena de texto.
  String uid;
  for (byte indice = 0; indice < lectorRFID.uid.size; indice++) {
    // Agrega un cero inicial si el byte solo tiene un digito hexadecimal.
    if (lectorRFID.uid.uidByte[indice] < 0x10) {
      uid += "0";
    }
    // Anade el byte actual en hexadecimal y normaliza el texto a mayusculas.
    uid += String(lectorRFID.uid.uidByte[indice], HEX);
    uid.toUpperCase();
    // Inserta dos puntos entre los bytes, pero no al final.
    if (indice < lectorRFID.uid.size - 1) {
      uid += ":";
    }
  }
  return uid;
}

void setup() {
  // Inicia el monitor serie a 115200 baudios.
  Serial.begin(115200);
  // Inicializa SPI con el orden SCK, MISO, MOSI y SS.
  SPI.begin(18, 19, 23, 5); // SCK, MISO, MOSI, SS
  // Inicializa el modulo RC522.
  lectorRFID.PCD_Init();
  // Ajusta la ganancia de recepcion de la antena al valor maximo.
  lectorRFID.PCD_SetAntennaGain(MFRC522::RxGain_max);
  Serial.println("Version del lector RC522:");
  lectorRFID.PCD_DumpVersionToSerial();
  Serial.println("Acerque una tarjeta o token RFID...");
}

void loop() {
  // Si no hay una credencial nueva, termina esta iteracion del ciclo.
  if (!lectorRFID.PICC_IsNewCardPresent()) {
    return;
  }

  // Lee el UID de la credencial detectada.
  if (!lectorRFID.PICC_ReadCardSerial()) {
    return;
  }

  Serial.print("UID: ");
  Serial.println(uidComoTexto());

  // Finaliza la comunicacion actual y espera antes de la siguiente lectura.
  lectorRFID.PICC_HaltA();
  lectorRFID.PCD_StopCrypto1();
  delay(500);
}
