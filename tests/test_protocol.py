"""Phase 1 Tier-A validation suite.

Proves the FieldGrade fail-safe spine in software, against the reference controller
in tools/controller_simulator.py (the software twin of firmware/src/main.cpp).

Each test maps to a Definition-of-Done item in PHASES_PLAN.md, Phase 1.
"""
from pathlib import Path
import json
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "tools"))

from controller_simulator import (  # noqa: E402
    ReferenceController, Config, crc16_ccitt, encode_command,
    FAULT_NONE, FAULT_TIMEOUT, FAULT_ESTOP, FAULT_BADFRAME,
)

DT = 10  # ms per control loop


class Harness:
    """Drives the controller like a 100 Hz command stream with a virtual clock."""
    def __init__(self, cfg=None):
        self.ctrl = ReferenceController(cfg=cfg or Config())
        self.t = 0
        self.seq = 0

    def send(self, mode, target_mm=0, manual=0, enable=True):
        self.seq += 1
        frame = encode_command(self.seq, mode, target_mm=target_mm, manual=manual,
                               enable=enable, ts_ms=self.t)
        self.ctrl.on_frame(frame)

    def tick(self):
        self.t += DT
        self.ctrl.tick(self.t)
        # invariant checked on every single loop: raise & lower never both energised
        assert not (self.ctrl.raise_duty > 0 and self.ctrl.lower_duty > 0)

    def drive(self, loops, mode, target_mm=0, manual=0, enable=True):
        for _ in range(loops):
            self.send(mode, target_mm=target_mm, manual=manual, enable=enable)
            self.tick()

    def coast(self, loops):
        """Advance time WITHOUT sending commands (to trigger timeout)."""
        for _ in range(loops):
            self.tick()


# ---- DoD 1: CRC parity with the Kotlin/firmware algorithm ----
def test_crc16_known_vector():
    # CRC-16/CCITT-FALSE("123456789") == 0x29B1 pins poly/init/reflection.
    assert crc16_ccitt(b"123456789") == 0x29B1

def test_encoded_frame_has_verifiable_crc():
    frame = encode_command(1, "AUTO", target_mm=-18, enable=True)
    doc = json.loads(frame)
    # recomputing CRC over the payload with crc16=0 must match the embedded value
    from controller_simulator import _canonical
    payload = _canonical(1, doc["seq"], doc["ts_ms"], doc["mode"],
                         doc["target_mm"], doc["manual"], doc["enable"], 0)
    assert doc["crc16"] == crc16_ccitt(payload.encode())


# ---- DoD 2: valid command -> expected bounded output ----
def test_manual_command_reaches_request():
    h = Harness()
    h.drive(40, "MANUAL", manual=250)
    assert h.ctrl.applied == 250
    assert h.ctrl.fault == FAULT_NONE
    assert h.ctrl.state == "ACTIVE"

def test_auto_output_is_gain_times_mm_and_clamped():
    h = Harness()
    # target 100 mm * gain 12 = 1200, clamped to MAX_DUTY 820
    h.drive(80, "AUTO", target_mm=100)
    assert h.ctrl.applied == h.ctrl.cfg.max_duty == 820

def test_hold_mode_is_neutral():
    h = Harness()
    h.drive(30, "HOLD", target_mm=50)
    assert h.ctrl.applied == 0
    assert h.ctrl.state == "NEUTRAL"


# ---- DoD 3: bad CRC -> neutral + fault 3 ----
def test_bad_crc_rejected():
    h = Harness()
    h.drive(20, "MANUAL", manual=200)          # get it moving
    frame = encode_command(999, "MANUAL", manual=200, enable=True)
    doc = json.loads(frame)
    doc["crc16"] = doc["crc16"] ^ 0xFFFF       # corrupt the CRC
    h.ctrl.on_frame(json.dumps(doc))
    assert h.ctrl.fault == FAULT_BADFRAME
    assert h.ctrl.enabled is False


# ---- DoD 4: stale / replayed sequence -> rejected ----
def test_stale_sequence_rejected():
    h = Harness()
    h.send("MANUAL", manual=100)               # seq 1
    h.tick()
    replay = encode_command(1, "MANUAL", manual=100, enable=True)  # same seq
    accepted = h.ctrl.on_frame(replay)
    assert accepted is False
    assert h.ctrl.fault == FAULT_BADFRAME

def test_reordered_lower_sequence_rejected():
    h = Harness()
    h.seq = 5
    h.send("MANUAL", manual=100)               # seq 6
    h.tick()
    old = encode_command(3, "MANUAL", manual=100, enable=True)     # lower than last
    assert h.ctrl.on_frame(old) is False


# ---- DoD 5: malformed frame -> neutral + fault 3 ----
def test_malformed_frame_rejected():
    h = Harness()
    h.drive(10, "MANUAL", manual=150)
    assert h.ctrl.on_frame("{not json") is False
    assert h.ctrl.fault == FAULT_BADFRAME
    assert h.ctrl.enabled is False

def test_wrong_version_rejected():
    h = Harness()
    frame = encode_command(1, "MANUAL", manual=100, enable=True, v=2)
    assert h.ctrl.on_frame(frame) is False
    assert h.ctrl.fault == FAULT_BADFRAME


# ---- DoD 6: command timeout -> neutral within 250 ms + fault 1 ----
def test_command_timeout_drives_neutral():
    h = Harness()
    h.drive(30, "MANUAL", manual=300)
    assert h.ctrl.applied != 0
    h.coast(40)                                 # 400 ms of silence > 250 ms
    assert h.ctrl.applied == 0
    assert h.ctrl.fault == FAULT_TIMEOUT

def test_neutral_within_250ms_of_comms_loss():
    # Comms loss must hard-zero (not ramp), so it is met even from full output.
    h = Harness()
    h.drive(60, "AUTO", target_mm=100)          # ramp to MAX_DUTY 820
    assert h.ctrl.applied == 820
    last_cmd_at = h.ctrl.last_valid_ms
    zero_at = None
    for _ in range(200):
        h.tick()                                # silence: no new commands
        if h.ctrl.applied == 0:
            zero_at = h.t
            break
    assert zero_at is not None
    assert (zero_at - last_cmd_at) <= h.ctrl.cfg.command_timeout_ms + DT

def test_timeout_is_hard_zero_not_ramp():
    # The loop before neutral is full output; the next is exactly zero (no ramp steps).
    h = Harness()
    h.drive(60, "AUTO", target_mm=100)
    assert h.ctrl.applied == 820
    prev = h.ctrl.applied
    for _ in range(200):
        prev = h.ctrl.applied
        h.tick()
        if h.ctrl.applied == 0:
            break
    assert prev == 820                          # jumped 820 -> 0 in a single loop


# ---- DoD 7: e-stop -> immediate hard zero + fault 2, regardless of command ----
def test_estop_hard_zeros_immediately():
    h = Harness()
    h.drive(60, "AUTO", target_mm=100)          # ramp to max 820
    assert h.ctrl.applied == 820
    h.ctrl.set_estop(True)
    h.send("AUTO", target_mm=100)               # operator/tablet still commanding
    h.tick()
    assert h.ctrl.applied == 0                  # NOT a ramp-down; immediate
    assert h.ctrl.fault == FAULT_ESTOP
    assert h.ctrl.state == "ESTOP"

def test_estop_overrides_command():
    h = Harness()
    h.ctrl.set_estop(True)
    h.drive(20, "MANUAL", manual=500)
    assert h.ctrl.applied == 0


# ---- DoD 8: enable=false -> neutral ----
def test_enable_false_is_neutral():
    h = Harness()
    h.drive(20, "MANUAL", manual=300)
    h.drive(40, "MANUAL", manual=300, enable=False)
    assert h.ctrl.applied == 0

def test_boot_state_is_disabled():
    ctrl = ReferenceController()
    assert ctrl.enabled is False
    assert ctrl.applied == 0
    ctrl.tick(10)
    assert ctrl.applied == 0                    # no command yet -> neutral


# ---- DoD 9 & 10: mutual exclusion + direction dead-time on reversal ----
def test_direction_reversal_has_deadtime_and_no_overlap():
    cfg = Config(direction_deadtime_ms=60)
    h = Harness(cfg=cfg)
    h.drive(30, "MANUAL", manual=200)           # raise to +200
    assert h.ctrl.applied > 0
    # command a reversal to lower; capture the trajectory
    reached_zero_at = None
    left_zero_negative_at = None
    for _ in range(120):
        h.send("MANUAL", manual=-200)
        h.tick()
        assert not (h.ctrl.raise_duty > 0 and h.ctrl.lower_duty > 0)   # never both
        if reached_zero_at is None and h.ctrl.applied == 0:
            reached_zero_at = h.t
        if reached_zero_at is not None and h.ctrl.applied < 0 and left_zero_negative_at is None:
            left_zero_negative_at = h.t
    assert reached_zero_at is not None and left_zero_negative_at is not None
    # must have held zero for at least the dead-time before going negative
    assert (left_zero_negative_at - reached_zero_at) >= cfg.direction_deadtime_ms


# ---- DoD 11: MAX_DUTY clamp + slew limit ----
def test_slew_rate_limited():
    cfg = Config(max_slew_per_loop=18)
    h = Harness(cfg=cfg)
    h.send("MANUAL", manual=800)
    prev = h.ctrl.applied
    for _ in range(10):
        h.send("MANUAL", manual=800)
        before = h.ctrl.applied
        h.tick()
        assert abs(h.ctrl.applied - before) <= cfg.max_slew_per_loop

def test_max_duty_clamp():
    h = Harness()
    h.drive(100, "MANUAL", manual=5000)
    assert h.ctrl.applied == h.ctrl.cfg.max_duty


# ---- DoD 12: status frame always reports fault; tablet cannot suppress ----
def test_status_frame_shape_and_fault_reporting():
    h = Harness()
    h.drive(20, "AUTO", target_mm=10)
    st = h.ctrl.status()
    for key in ("v", "seq_ack", "state", "output", "estop", "fault", "age_ms"):
        assert key in st
    h.ctrl.set_estop(True)
    h.tick()
    assert h.ctrl.status()["fault"] == FAULT_ESTOP   # reported, not hidden


# ---- Integration: the stdin/stdout simulator still works end to end ----
def test_simulator_subprocess_manual_command():
    sim = Path(__file__).parents[1] / "tools" / "controller_simulator.py"
    p = subprocess.Popen([sys.executable, str(sim)], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, text=True)
    try:
        p.stdout.readline()                      # banner
        p.stdin.write(encode_command(1, "MANUAL", manual=18, enable=True) + "\n")
        p.stdin.flush()
        status = json.loads(p.stdout.readline())
        assert status["output"] == 18            # slew 18/loop reaches 18 in one tick
        assert status["fault"] == FAULT_NONE
    finally:
        p.terminate()
