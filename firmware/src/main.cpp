#include <Arduino.h>
#include <ArduinoJson.h>
#include "config.h"

// FieldGrade ESP32 controller firmware.
//
// Safety spine (see PROJECT_PLAN.md section 4 and docs/CONTROL_PROTOCOL.md):
//   - neutral by default; any doubt -> neutral
//   - CRC-16/CCITT verified on every frame; monotonic sequence (anti-replay)
//   - 250 ms command timeout -> neutral (fault 1)
//   - hardware e-stop -> immediate hard zero (fault 2), bypasses the slew limiter
//   - raise/lower are structurally mutually exclusive; dead-time on direction reversal
//   - controller-side gain, MAX_DUTY clamp and slew limit
//   - status frame emitted every loop so the tablet is never blind
//
// NOTE: ledcSetup/ledcAttachPin below target ESP32 Arduino core 2.x (matches the
// pinned toolchain). On core 3.x replace with ledcAttach(pin, freq, resBits).

static uint32_t lastValidCommandMs = 0;
static long     lastSeq            = -1;     // last accepted sequence number
static int      requestedDuty      = 0;      // signed duty derived from last valid command
static int      appliedOutput      = 0;      // signed duty currently applied
static bool     enabled            = false;
static uint32_t deadtimeUntilMs    = 0;      // no opposite-direction output before this time
static int      faultCode          = FAULT_NONE;

// ---- CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), matches the tablet encoder ----
static uint16_t crc16Ccitt(const uint8_t* data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= (uint16_t)data[i] << 8;
    for (int b = 0; b < 8; b++) {
      crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
  }
  return crc;
}

// Rebuild the canonical payload with crc16=0 in the exact field order the tablet
// uses, then CRC it. Must byte-match the tablet's kotlinx.serialization output.
static uint16_t expectedCrc(long v, long seq, long ts_ms, const char* mode,
                            long target_mm, long manual, bool enable) {
  char buf[192];
  int n = snprintf(buf, sizeof(buf),
    "{\"v\":%ld,\"seq\":%ld,\"ts_ms\":%ld,\"mode\":\"%s\",\"target_mm\":%ld,"
    "\"manual\":%ld,\"enable\":%s,\"crc16\":0}",
    v, seq, ts_ms, mode, target_mm, manual, enable ? "true" : "false");
  if (n <= 0) return 0;
  return crc16Ccitt((const uint8_t*)buf, (size_t)n);
}

static void hardNeutral(int fault) {   // e-stop / brownout / boot: immediate, bypass slew
  requestedDuty   = 0;
  appliedOutput   = 0;
  enabled         = false;
  faultCode       = fault;
  ledcWrite(0, 0);
  ledcWrite(1, 0);
}

static void requestNeutral(int fault) { // comms/frame fault: stop requesting, let slew ramp down
  requestedDuty = 0;
  enabled       = false;
  faultCode     = fault;
}

static int targetDutyFor(const char* mode, long target_mm, long manual) {
  if (strcmp(mode, "MANUAL") == 0) return constrain((int)manual, -MAX_DUTY, MAX_DUTY);
  if (strcmp(mode, "AUTO")   == 0) return constrain((int)(target_mm * GAIN_PER_MM), -MAX_DUTY, MAX_DUTY);
  return 0; // HOLD
}

static int sgn(int x) { return (x > 0) - (x < 0); }

// Drive the output toward `target` under all limits. Called every loop.
static void applyOutput(int target) {
  if (digitalRead(PIN_ESTOP) == LOW || !enabled) target = 0;

  // Direction dead-time / mutual exclusion: never jump across zero directly.
  if (target != 0 && appliedOutput != 0 && sgn(target) != sgn(appliedOutput)) target = 0;
  if (appliedOutput == 0 && target != 0 && millis() < deadtimeUntilMs)       target = 0;

  target = constrain(target, -MAX_DUTY, MAX_DUTY);
  int delta = constrain(target - appliedOutput, -MAX_SLEW_PER_LOOP, MAX_SLEW_PER_LOOP);
  int prev = appliedOutput;
  appliedOutput += delta;

  if (prev != 0 && appliedOutput == 0) deadtimeUntilMs = millis() + DIRECTION_DEADTIME_MS;

  // Structurally mutually exclusive: one channel is always zero.
  ledcWrite(0, appliedOutput > 0 ?  appliedOutput : 0);
  ledcWrite(1, appliedOutput < 0 ? -appliedOutput : 0);
}

static bool parseCommand(const String& line) {
  JsonDocument doc;
  if (deserializeJson(doc, line)) return false;

  int version = doc["v"] | 0;
  if (version != PROTO_VERSION) return false;
  if (!doc["crc16"].is<long>()) return false;

  long seq       = doc["seq"]       | -1;
  long ts_ms     = doc["ts_ms"]     | 0;
  const char* mode = doc["mode"]    | "HOLD";
  long target_mm = doc["target_mm"] | 0;
  long manual    = doc["manual"]    | 0;
  bool en        = doc["enable"]    | false;
  long crc       = doc["crc16"]     | 0;

  if ((uint16_t)crc != expectedCrc(version, seq, ts_ms, mode, target_mm, manual, en)) return false;
  if (seq <= lastSeq) return false;    // stale / replayed

  lastSeq            = seq;
  enabled            = en;
  requestedDuty      = targetDutyFor(mode, target_mm, manual);
  lastValidCommandMs = millis();
  faultCode          = FAULT_NONE;
  return true;
}

static int canonicalStatus(char* buf, size_t n, long seq_ack, const char* state,
                           int output, int supply_mv, bool estop, int fault,
                           long age_ms, unsigned crc) {
  return snprintf(buf, n,
    "{\"v\":%d,\"seq_ack\":%ld,\"state\":\"%s\",\"output\":%d,\"supply_mv\":%d,"
    "\"estop\":%s,\"fault\":%d,\"age_ms\":%ld,\"crc16\":%u}",
    PROTO_VERSION, seq_ack, state, output, supply_mv,
    estop ? "true" : "false", fault, age_ms, crc);
}

static void emitStatus() {
  bool estop = (digitalRead(PIN_ESTOP) == LOW);
  const char* state = estop ? "ESTOP" : (appliedOutput != 0) ? "ACTIVE" : "NEUTRAL";
  int supply_mv = 0;  // placeholder until the supply-voltage divider is instrumented
  long age = (long)(millis() - lastValidCommandMs);

  char buf[224];
  int len = canonicalStatus(buf, sizeof(buf), lastSeq, state, appliedOutput,
                            supply_mv, estop, faultCode, age, 0);
  uint16_t crc = (len > 0) ? crc16Ccitt((const uint8_t*)buf, (size_t)len) : 0;
  canonicalStatus(buf, sizeof(buf), lastSeq, state, appliedOutput,
                  supply_mv, estop, faultCode, age, crc);
  Serial.print(buf);
  Serial.print('\n');
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_ESTOP, INPUT_PULLUP);
  ledcSetup(0, PWM_FREQUENCY_HZ, PWM_RESOLUTION_BITS);
  ledcSetup(1, PWM_FREQUENCY_HZ, PWM_RESOLUTION_BITS);
  ledcAttachPin(PIN_PWM_RAISE, 0);
  ledcAttachPin(PIN_PWM_LOWER, 1);
  hardNeutral(FAULT_NONE);   // boot -> neutral, disabled until deliberate re-enable
}

void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    if (line.length() > 0 && !parseCommand(line)) requestNeutral(FAULT_BADFRAME);
  }

  if (digitalRead(PIN_ESTOP) == LOW) {
    hardNeutral(FAULT_ESTOP);                 // immediate, bypasses slew
  } else if (millis() - lastValidCommandMs >= COMMAND_TIMEOUT_MS) {
    // Comms loss is a fault of the same class as e-stop: stop, do not coast down.
    // Hard-zero is what meets "neutral within 250 ms of communication loss".
    hardNeutral(FAULT_TIMEOUT);
  }

  applyOutput(enabled ? requestedDuty : 0);
  emitStatus();
  delay(LOOP_MS);
}
