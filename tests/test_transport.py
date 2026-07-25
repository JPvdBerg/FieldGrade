"""Phase 2 cross-stack integration: tablet command stream <-> reference controller.

These run against the same reference controller the firmware mirrors, proving the
transport contract end to end in software (Tier A). The Kotlin unit tests
(android/app/src/test/.../TransportTest.kt) prove the tablet-side logic itself.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "tools"))

from controller_simulator import (  # noqa: E402
    ReferenceController, encode_command, encode_status,
    crc16_ccitt, _canonical_status, FAULT_TIMEOUT,
)

DT = 10


def _verify_status(line: str) -> dict:
    """Tablet-side status decode with CRC verification. Returns fields or raises."""
    import json
    doc = json.loads(line)
    crc = doc.pop("crc16")
    canonical = _canonical_status(doc["v"], doc["seq_ack"], doc["state"], doc["output"],
                                  doc["supply_mv"], doc["estop"], doc["fault"], doc["age_ms"], 0)
    assert crc16_ccitt(canonical.encode()) == crc, "status CRC mismatch"
    return doc


def test_command_roundtrip_and_status_crc():
    ctrl = ReferenceController()
    t = 0
    for seq in range(1, 6):
        ctrl.on_frame(encode_command(seq, "MANUAL", manual=200, enable=True, ts_ms=t))
        t += DT
        ctrl.tick(t)
    line = ctrl.status_line()
    fields = _verify_status(line)          # CRC verified on receive
    assert fields["state"] == "ACTIVE"
    assert fields["output"] > 0
    assert fields["seq_ack"] == 5


def test_status_crc_detects_corruption():
    line = encode_status(1, "ACTIVE", -100, 13000, False, 0, 5)
    corrupt = line.replace('"output":-100', '"output":100')  # tamper, keep old crc
    import pytest
    with pytest.raises(AssertionError):
        _verify_status(corrupt)


def test_link_loss_drives_neutral_within_250ms_end_to_end():
    """Tablet streams -> active output; link drops (tablet stops sending) ->
    controller must reach neutral within 250 ms."""
    ctrl = ReferenceController()
    t = 0
    # healthy 25 Hz-ish stream to full activity
    for seq in range(1, 40):
        ctrl.on_frame(encode_command(seq, "AUTO", target_mm=100, enable=True, ts_ms=t))
        t += DT
        ctrl.tick(t)
    assert ctrl.applied != 0
    last_cmd_at = ctrl.last_valid_ms

    # LINK LOSS: no more commands are sent. Only the control loop keeps running.
    zero_at = None
    for _ in range(200):
        t += DT
        ctrl.tick(t)
        if ctrl.applied == 0:
            zero_at = t
            break
    assert zero_at is not None
    assert (zero_at - last_cmd_at) <= ctrl.cfg.command_timeout_ms + DT
    assert ctrl.fault == FAULT_TIMEOUT
