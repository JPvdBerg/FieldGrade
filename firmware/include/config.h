#pragma once

constexpr int PIN_PWM_RAISE = 25;
constexpr int PIN_PWM_LOWER = 26;
constexpr int PIN_ESTOP = 27;
constexpr uint32_t COMMAND_TIMEOUT_MS = 250;
constexpr int PWM_FREQUENCY_HZ = 1000;
constexpr int PWM_RESOLUTION_BITS = 10;
constexpr int MAX_DUTY = 820;       // commissioning limit, not a final field value
constexpr int MAX_SLEW_PER_LOOP = 18;
