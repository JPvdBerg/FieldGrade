# FieldGrade Tablet-to-Controller Protocol v0.1

## Transport

The first implementation supports USB serial or Bluetooth SPP at 115200 baud. Each frame is newline-delimited UTF-8 JSON during prototyping. A compact binary frame can replace it later without changing the application service boundary.

## Command frame

```json
{"v":1,"seq":1042,"ts_ms":1720951200000,"mode":"AUTO","target_mm":-18,"manual":0,"enable":true,"crc16":12345}
```

Fields:

- `v`: protocol version.
- `seq`: monotonically increasing sequence number.
- `ts_ms`: tablet timestamp for diagnostics only.
- `mode`: `HOLD`, `MANUAL`, or `AUTO`.
- `target_mm`: signed vertical correction. Negative means lower; positive means raise.
- `manual`: signed manual request from -1000 to +1000.
- `enable`: hydraulic enable request.
- `crc16`: CRC-16/CCITT over the canonical payload excluding `crc16`.

## Status frame

```json
{"v":1,"seq_ack":1042,"state":"ACTIVE","output":-274,"supply_mv":13780,"estop":false,"fault":0,"age_ms":34}
```

## Mandatory controller rules

1. Any invalid CRC, stale sequence, malformed frame, or command timeout drives output to neutral.
2. Default command timeout is 250 ms.
3. `enable=false`, emergency stop, boot, brownout, or transport loss forces neutral.
4. Output slew rate and maximum duty cycle are controller-side limits.
5. AUTO uses a deadband and proportional response; final gains are machine-specific commissioning values.
6. The controller reports all fault causes; the tablet never suppresses a safety fault.

## Fault codes

- `0`: none
- `1`: command timeout
- `2`: emergency stop
- `3`: bad frame/CRC
- `4`: supply voltage out of range
- `5`: output driver fault
- `6`: sensor disagreement
- `7`: internal watchdog reset
