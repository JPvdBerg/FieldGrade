#include <Arduino.h>
#include <ArduinoJson.h>
#include "config.h"

static uint32_t lastValidCommandMs = 0;
static int requestedOutput = 0;
static int appliedOutput = 0;
static bool enabled = false;

void neutral() {
  requestedOutput = 0;
  enabled = false;
}

void applyOutput(int signedDuty) {
  signedDuty = constrain(signedDuty, -MAX_DUTY, MAX_DUTY);
  if (digitalRead(PIN_ESTOP) == LOW || !enabled) signedDuty = 0;

  int delta = signedDuty - appliedOutput;
  delta = constrain(delta, -MAX_SLEW_PER_LOOP, MAX_SLEW_PER_LOOP);
  appliedOutput += delta;

  ledcWrite(0, appliedOutput > 0 ? appliedOutput : 0);
  ledcWrite(1, appliedOutput < 0 ? -appliedOutput : 0);
}

bool parseCommand(const String& line) {
  JsonDocument doc;
  if (deserializeJson(doc, line)) return false;
  if (doc["v"] | 0 != 1) return false;
  enabled = doc["enable"] | false;
  const char* mode = doc["mode"] | "HOLD";
  int target = doc["target_mm"] | 0;
  int manual = doc["manual"] | 0;
  requestedOutput = strcmp(mode, "MANUAL") == 0 ? manual : target * 12;
  requestedOutput = constrain(requestedOutput, -1000, 1000);
  lastValidCommandMs = millis();
  return true;
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_ESTOP, INPUT_PULLUP);
  ledcSetup(0, PWM_FREQUENCY_HZ, PWM_RESOLUTION_BITS);
  ledcSetup(1, PWM_FREQUENCY_HZ, PWM_RESOLUTION_BITS);
  ledcAttachPin(PIN_PWM_RAISE, 0);
  ledcAttachPin(PIN_PWM_LOWER, 1);
  neutral();
}

void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('
');
    if (!parseCommand(line)) neutral();
  }
  if (millis() - lastValidCommandMs > COMMAND_TIMEOUT_MS) neutral();
  if (digitalRead(PIN_ESTOP) == LOW) neutral();
  applyOutput(enabled ? requestedOutput : 0);
  delay(10);
}
