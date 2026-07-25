#pragma once

// ---- Pin map ----
constexpr int PIN_PWM_RAISE = 25;
constexpr int PIN_PWM_LOWER = 26;
constexpr int PIN_ESTOP     = 27;   // active-LOW; also cuts valve power in hardware (2nd line of defence)

// ---- Timing ----
constexpr uint32_t COMMAND_TIMEOUT_MS    = 250; // silence longer than this -> neutral (fault 1)
constexpr uint32_t LOOP_MS               = 10;  // control loop period
constexpr uint32_t DIRECTION_DEADTIME_MS = 60;  // both channels held at 0 when reversing direction

// ---- PWM ----
constexpr int PWM_FREQUENCY_HZ    = 1000;
constexpr int PWM_RESOLUTION_BITS = 10;         // 0..1023

// ---- Output limits (COMMISSIONING PLACEHOLDERS, not final field values) ----
constexpr int MAX_DUTY          = 820;          // hard clamp on |output|
constexpr int MAX_SLEW_PER_LOOP = 18;           // max change in output per LOOP_MS
constexpr int GAIN_PER_MM       = 12;           // AUTO: duty = target_mm * GAIN_PER_MM (ALL gain lives here)

// ---- Protocol ----
constexpr int PROTO_VERSION = 1;

// ---- Fault codes (see docs/CONTROL_PROTOCOL.md) ----
constexpr int FAULT_NONE     = 0;
constexpr int FAULT_TIMEOUT  = 1;
constexpr int FAULT_ESTOP    = 2;
constexpr int FAULT_BADFRAME = 3;
constexpr int FAULT_SUPPLY   = 4;
constexpr int FAULT_DRIVER   = 5;
constexpr int FAULT_SENSOR   = 6;
constexpr int FAULT_WATCHDOG = 7;
