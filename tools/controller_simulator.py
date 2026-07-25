#!/usr/bin/env python3
"""FieldGrade controller reference model + desktop simulator.

This is the software twin of firmware/src/main.cpp. It enforces the SAME safety
rules so the tablet protocol and the fail-safe logic can be validated without any
hardware (Phase 1, Tier A). The firmware is the authority on the machine; this
model is the authority for the automated test suite.

Rules enforced (PROJECT_PLAN.md section 4, docs/CONTROL_PROTOCOL.md):
  * CRC-16/CCITT verified on every frame; must byte-match the tablet encoder.
  * Monotonic sequence number (anti-replay / anti-reorder).
  * 250 ms command timeout -> neutral (fault 1).
  * E-stop -> immediate hard zero (fault 2), bypasses the slew limiter.
  * enable=false / boot -> neutral.
  * Raise and lower are structurally mutually exclusive.
  * Dead-time on direction reversal.
  * Controller-side gain, MAX_DUTY clamp and per-loop slew limit.

Run as a stdin/stdout simulator:
    python tools/controller_simulator.py
"""
from __future__ import annotations
import json
import sys
from dataclasses import dataclass, field

# ---- Fault codes (mirror config.h / CONTROL_PROTOCOL.md) ----
FAULT_NONE, FAULT_TIMEOUT, FAULT_ESTOP, FAULT_BADFRAME = 0, 1, 2, 3


def crc16_ccitt(data: bytes) -> int:
    """CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xorout.
    Byte-identical to ControllerProtocol.crc16Ccitt in the Android app and to
    crc16Ccitt in firmware/src/main.cpp."""
    crc = 0xFFFF
    for b in data:
        crc ^= (b & 0xFF) << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc & 0xFFFF


def _canonical(v, seq, ts_ms, mode, target_mm, manual, enable, crc16) -> str:
    """Reproduce the tablet's canonical JSON exactly (no spaces, declaration order,
    lowercase booleans). Used for both encoding and CRC verification."""
    return (
        '{"v":%d,"seq":%d,"ts_ms":%d,"mode":"%s","target_mm":%d,'
        '"manual":%d,"enable":%s,"crc16":%d}'
    ) % (v, seq, ts_ms, mode, target_mm, manual, "true" if enable else "false", crc16)


def encode_command(seq, mode, target_mm=0, manual=0, enable=False, ts_ms=0, v=1) -> str:
    """Build a wire frame with a valid CRC. Mirrors ControllerProtocol.encode."""
    payload = _canonical(v, seq, ts_ms, mode, target_mm, manual, enable, 0)
    crc = crc16_ccitt(payload.encode("utf-8"))
    return _canonical(v, seq, ts_ms, mode, target_mm, manual, enable, crc)


def _canonical_status(v, seq_ack, state, output, supply_mv, estop, fault, age_ms, crc16) -> str:
    """Reproduce the controller's canonical status JSON exactly (field order,
    no spaces, lowercase booleans). Used for encoding and CRC verification."""
    return (
        '{"v":%d,"seq_ack":%d,"state":"%s","output":%d,"supply_mv":%d,'
        '"estop":%s,"fault":%d,"age_ms":%d,"crc16":%d}'
    ) % (v, seq_ack, state, output, supply_mv,
         "true" if estop else "false", fault, age_ms, crc16)


def encode_status(seq_ack, state, output, supply_mv, estop, fault, age_ms, v=1) -> str:
    """Build a status wire frame with a valid CRC (mirrors firmware emitStatus)."""
    payload = _canonical_status(v, seq_ack, state, output, supply_mv, estop, fault, age_ms, 0)
    crc = crc16_ccitt(payload.encode("utf-8"))
    return _canonical_status(v, seq_ack, state, output, supply_mv, estop, fault, age_ms, crc)


@dataclass
class Config:
    max_duty: int = 820
    max_slew_per_loop: int = 18
    command_timeout_ms: int = 250
    gain_per_mm: int = 12
    direction_deadtime_ms: int = 60


def _sgn(x: int) -> int:
    return (x > 0) - (x < 0)


@dataclass
class ReferenceController:
    """Deterministic, virtual-clock model of the ESP32 controller."""
    cfg: Config = field(default_factory=Config)
    now_ms: int = 0
    last_valid_ms: int = -10 ** 9
    last_seq: int = -1
    enabled: bool = False
    requested_duty: int = 0
    applied: int = 0
    estop: bool = False
    fault: int = FAULT_NONE
    _deadtime_until_ms: int = 0

    # ---- inputs ----
    def set_estop(self, asserted: bool) -> None:
        self.estop = asserted

    def on_frame(self, line: str) -> bool:
        """Validate and apply a command frame. Returns True if accepted.
        Any rejection drives neutral and sets a fault, never raises."""
        try:
            doc = json.loads(line)
        except Exception:
            self._request_neutral(FAULT_BADFRAME)
            return False

        if not isinstance(doc, dict) or doc.get("v") != 1 or "crc16" not in doc:
            self._request_neutral(FAULT_BADFRAME)
            return False

        try:
            v, seq, ts = int(doc["v"]), int(doc["seq"]), int(doc.get("ts_ms", 0))
            mode = str(doc["mode"])
            target_mm, manual = int(doc.get("target_mm", 0)), int(doc.get("manual", 0))
            enable, crc = bool(doc["enable"]), int(doc["crc16"])
        except (KeyError, ValueError, TypeError):
            self._request_neutral(FAULT_BADFRAME)
            return False

        expected = crc16_ccitt(_canonical(v, seq, ts, mode, target_mm, manual, enable, 0).encode())
        if crc != expected:
            self._request_neutral(FAULT_BADFRAME)
            return False
        if seq <= self.last_seq:          # stale / replayed / reordered
            self._request_neutral(FAULT_BADFRAME)
            return False

        self.last_seq = seq
        self.enabled = enable
        self.requested_duty = self._target_duty(mode, target_mm, manual)
        self.last_valid_ms = self.now_ms
        self.fault = FAULT_NONE
        return True

    # ---- internal ----
    def _target_duty(self, mode: str, target_mm: int, manual: int) -> int:
        if mode == "MANUAL":
            base = manual                            # direct request, no gain
        elif mode == "AUTO":
            base = target_mm * self.cfg.gain_per_mm  # ALL gain lives here
        else:
            base = 0                                 # HOLD
        return max(-self.cfg.max_duty, min(self.cfg.max_duty, base))

    def _request_neutral(self, fault: int) -> None:
        self.requested_duty = 0
        self.enabled = False
        self.fault = fault

    def _hard_zero(self, fault: int) -> None:
        self.requested_duty = 0
        self.applied = 0
        self.enabled = False
        self.fault = fault

    def tick(self, now_ms: int) -> None:
        """Advance the control loop to `now_ms` and update the output."""
        self.now_ms = now_ms

        if self.estop:
            self._hard_zero(FAULT_ESTOP)          # immediate, bypass slew
            return
        if self.now_ms - self.last_valid_ms >= self.cfg.command_timeout_ms:
            # Comms loss is a fault of the same safety class as e-stop: the
            # supervising link is gone, so motion must STOP, not coast down.
            # Hard-zero here is what meets "neutral within 250 ms of comms loss".
            self._hard_zero(FAULT_TIMEOUT)
            return

        target = self.requested_duty if self.enabled else 0

        # Direction dead-time / mutual exclusion: never cross zero directly.
        if target != 0 and self.applied != 0 and _sgn(target) != _sgn(self.applied):
            target = 0
        if self.applied == 0 and target != 0 and self.now_ms < self._deadtime_until_ms:
            target = 0

        delta = max(-self.cfg.max_slew_per_loop,
                    min(self.cfg.max_slew_per_loop, target - self.applied))
        prev = self.applied
        self.applied += delta
        if prev != 0 and self.applied == 0:
            self._deadtime_until_ms = self.now_ms + self.cfg.direction_deadtime_ms

    # ---- outputs ----
    @property
    def raise_duty(self) -> int:
        return self.applied if self.applied > 0 else 0

    @property
    def lower_duty(self) -> int:
        return -self.applied if self.applied < 0 else 0

    @property
    def state(self) -> str:
        if self.estop:
            return "ESTOP"
        return "ACTIVE" if self.applied != 0 else "NEUTRAL"

    def status(self) -> dict:
        return {
            "v": 1,
            "seq_ack": self.last_seq,
            "state": self.state,
            "output": self.applied,
            "supply_mv": 0,
            "estop": self.estop,
            "fault": self.fault,
            "age_ms": max(0, self.now_ms - self.last_valid_ms),
        }

    def status_line(self) -> str:
        """CRC-protected status wire frame (what the controller actually sends)."""
        s = self.status()
        return encode_status(s["seq_ack"], s["state"], s["output"], s["supply_mv"],
                             s["estop"], s["fault"], s["age_ms"])


def main() -> None:
    print("Controller simulator ready; send one JSON command per line.", flush=True)
    ctrl = ReferenceController()
    t = 0
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        ctrl.on_frame(line)
        t += 10
        ctrl.tick(t)
        print(ctrl.status_line(), flush=True)


if __name__ == "__main__":
    main()
