#!/usr/bin/env python3
"""Inspect the reviewed external sensor-23 VEM subset, never encode a model.

Evidence/provenance and deliberate limits: docs/research/ev-external-model-contract.md.
This is a local wire inspector, not Google's decoder or a route evaluator.
"""
import argparse
import json
import math
import struct
import sys

# (wire type, evidence-backed name, scalar kind or nested contract)
INTEGER = {1: (0, "mean", "int32"), 2: (5, "stddev", "float")}
FLOAT = {1: (5, "mean", "float"), 2: (5, "stddev", "float")}
VEHICLE = {
    3: (2, "current_energy_wh", INTEGER),
    4: (2, "capacity_wh", INTEGER),
    8: (5, "battery_temperature_c", "float"),
    9: (0, "peak_motor_power_w", "uint32"),
    10: (0, "max_charging_rate_w", "uint32"),
    11: (0, "heat_pump_equipped", "bool"),
}
ROAD = {
    1: (2, "constant", FLOAT),
    2: (2, "linear", FLOAT),
    3: (2, "quadratic", FLOAT),
}
EXTERNAL = {1: (2, "vehicle_info", VEHICLE), 2: (2, "road_load", ROAD)}


MAX_BYTES = 65536
MAX_FIELDS = 1024
MAX_DEPTH = 8


class WireError(ValueError):
    """Malformed, unsupported wire encoding or an exceeded inspection budget."""


def _varint(data, offset):
    value = 0
    for index in range(10):
        if offset >= len(data):
            raise WireError("truncated varint")
        byte = data[offset]
        offset += 1
        if index == 9 and byte > 1:
            raise WireError("varint exceeds uint64")
        value |= (byte & 127) << (7 * index)
        if byte < 128:
            return value, offset
    raise WireError("unterminated varint")


def inspect_vem(payload, *, max_bytes=MAX_BYTES, max_fields=MAX_FIELDS,
                max_depth=MAX_DEPTH):
    """Inspect bytes with hard ceilings; callers may only reduce the budgets.

    Every occurrence is retained in wire order; no protobuf merge or last-wins
    semantics are applied. Unknown length-delimited payloads remain opaque.
    """
    if not (0 <= max_bytes <= MAX_BYTES and 0 <= max_fields <= MAX_FIELDS
            and 0 <= max_depth <= MAX_DEPTH):
        raise WireError("inspection limits must be within hard ceilings")
    if len(payload) > max_bytes:
        raise WireError("payload exceeds byte budget")
    fields = []
    absent = []

    def walk(data, contract, prefix="", depth=0):
        if depth > max_depth:
            raise WireError("message exceeds depth budget")
        offset = 0
        seen = set()
        while offset < len(data):
            if len(fields) >= max_fields:
                raise WireError("message exceeds field budget")
            start = offset
            tag, offset = _varint(data, offset)
            number, wire = tag >> 3, tag & 7
            if not 1 <= number <= 0x1fffffff:
                raise WireError("invalid field number")
            if wire not in (0, 1, 2, 5):
                raise WireError("unsupported wire type (groups are not inspected)")
            seen.add(number)
            path = f"{prefix}.{number}" if prefix else str(number)
            if wire == 0:
                value, offset = _varint(data, offset)
            else:
                if wire == 2:
                    size, offset = _varint(data, offset)
                else:
                    size = {1: 8, 5: 4}[wire]
                if size > len(data) - offset:
                    raise WireError(f"truncated field {path}")
                value = data[offset:offset + size]
                offset += size
            item = {"path": path, "wire": wire, "raw_hex": data[start:offset].hex()}
            fields.append(item)
            if number not in contract:
                item["status"] = "unknown"
                item["note"] = (
                    "absent from reviewed external descriptor; legacy meaning unsupported"
                    if path in ("1.1", "12") else "outside reviewed subset; meaning not inferred"
                )
                continue
            expected, name, kind = contract[number]
            item.update(expected_wire=expected, name=name, status="compatible")
            if wire != expected:
                item["status"] = "wrong-wire"
                continue
            if isinstance(kind, dict):
                walk(value, kind, path, depth + 1)
            elif kind == "float":
                item["value"] = struct.unpack("<f", value)[0]
                if not math.isfinite(item["value"]):
                    item.update(status="invalid-value", value=str(item["value"]),
                                note="nonfinite float; no physical meaning inferred")
            elif kind == "bool":
                item["value"] = bool(value)
            else:
                item["value"] = value
                if kind == "uint32" and value > 0xffffffff:
                    item.update(status="invalid-value", note="outside uint32 domain")
                elif kind == "int32":
                    # Accept 32-bit patterns and canonical sign-extended negatives.
                    if value <= 0xffffffff or value >= 0xffffffff80000000:
                        low = value & 0xffffffff
                        item["value"] = low - (1 << 32) if low & 0x80000000 else low
                    else:
                        item.update(status="invalid-value", note="outside int32 domain")
        absent.extend(f"{prefix}.{n}" if prefix else str(n)
                      for n in contract if n not in seen)

    walk(payload, EXTERNAL)
    return {"fields": fields, "absent": absent}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--hex", help="raw VEM hex, without SensorBatch/framing")
    source.add_argument("--file", help="binary raw VEM file (read-only)")
    args = parser.parse_args(argv)
    try:
        if args.hex is not None:
            if len(args.hex) > MAX_BYTES * 3:
                raise WireError("hex text exceeds input budget")
            payload = bytes.fromhex(args.hex)
        else:
            with open(args.file, "rb") as stream:
                payload = stream.read(MAX_BYTES + 1)
        report = inspect_vem(payload)
    except (ValueError, OSError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, allow_nan=False))
    return int(any(f["status"] in ("wrong-wire", "invalid-value")
                   for f in report["fields"]))


if __name__ == "__main__":
    sys.exit(main())
