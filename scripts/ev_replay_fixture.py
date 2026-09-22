#!/usr/bin/env python3
"""Build a deterministic, pseudonymized EV semantic replay fixture from an upload ZIP.

The private archive contains output from the old recorder. This tool discards old
computed outputs and retains only fields that can be replayed as inputs to the
current EvTelemetryRecorder/EvTelemetryCore semantics. The allowlist removes
direct identifiers but retained cadence and value sequences still carry residual
within-fixture linkage risk; see docs/ev-real-upload-replay.md.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import io
import json
import pathlib
import re
import zipfile

REPLAY_TYPES = {
    "consent_start", "session_start", "gap", "requested_settings", "vehicle",
    "forecast", "forecast_empty", "navigation_status", "route_cancel_or_reset",
    "navigation",
}
EVENT_KEYS = {
    "type", "t", "session", "distanceM", "etaSec", "active", "arrivalWh",
    "quality", "receivedT", "speedKmh", "batteryWh", "gearRaw", "observations",
    "effective", "requested",
}
OBSERVATION_KEYS = {"source", "timestampNanos", "receivedT", "status"}
SETTINGS_KEYS = {"enabled", "mode"}
EFFECTIVE_KEYS = {"requested", "enabled", "mode", "observerStarted", "learnerState"}
META_KEYS = {
    "format", "release", "sourceSha256", "sourceRecordCount", "sourceTypeCounts",
    "eventCount", "sourceOldCompletionRecords",
}
UUID_RE = re.compile(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b")
SENSITIVE_VALUE_RE = re.compile(
    r"(?i)(?:\b(?:vin|latitude|longitude|destination|road|street|avenue|household)\b|"
    r"(?:\b\d{1,3}\.){3}\d{1,3}\b|(?:[0-9a-f]{2}:){5}[0-9a-f]{2})"
)


def _settings(value):
    if not isinstance(value, dict):
        return None
    return {"enabled": value.get("enabled"), "mode": value.get("mode")}


def _effective(value):
    if not isinstance(value, dict):
        return None
    return {
        "requested": _settings(value.get("requested")),
        "enabled": value.get("enabled"),
        "mode": value.get("mode"),
        "observerStarted": value.get("observerStarted"),
        "learnerState": value.get("learnerState"),
    }


def _observation(value, base_ms):
    if not isinstance(value, dict):
        return None
    timestamp = value.get("timestampElapsedNanos")
    received = value.get("receivedElapsedMs")
    return {
        "source": value.get("source"),
        "timestampNanos": timestamp - base_ms * 1_000_000 if isinstance(timestamp, int) else None,
        "receivedT": received - base_ms if isinstance(received, int) else None,
        "status": value.get("status"),
    }


def build_fixture(records, source_sha256, release):
    records = list(records)
    if not records:
        base_ms = 0
    else:
        base_ms = min(r["elapsedMs"] for r in records if isinstance(r.get("elapsedMs"), int))
    type_counts = collections.Counter(r.get("type") for r in records)
    sessions = {}

    def session_alias(raw):
        if raw is None:
            return "none"
        if raw not in sessions:
            sessions[raw] = f"s{len(sessions) + 1}"
        return sessions[raw]

    events = []
    for record in records:
        old_type = record.get("type")
        if old_type not in REPLAY_TYPES:
            continue
        event = {
            "type": old_type,
            "t": record["elapsedMs"] - base_ms,
            "session": session_alias(record.get("session")),
        }
        if old_type == "gap":
            event["type"] = "gap"
        elif old_type == "requested_settings":
            event["requested"] = _settings(record.get("requested"))
        elif old_type == "navigation":
            event["distanceM"] = record.get("remainingM")
            event["etaSec"] = record.get("etaSec")
        elif old_type == "navigation_status":
            event["type"] = "route_lifecycle"
            event["active"] = record.get("status") == 1
        elif old_type == "route_cancel_or_reset":
            event["type"] = "route_reset"
        elif old_type in {"forecast", "forecast_empty"}:
            event["arrivalWh"] = record.get("latestArrivalWh") if old_type == "forecast" else None
            event["distanceM"] = record.get("distanceM")
            event["etaSec"] = record.get("etaSec")
            event["quality"] = record.get("quality") or 0
            received = record.get("receivedAtElapsedMs")
            event["receivedT"] = received - base_ms if isinstance(received, int) else event["t"]
        elif old_type == "vehicle":
            event["speedKmh"] = record.get("speedKmh")
            event["batteryWh"] = record.get("batteryWh")
            event["gearRaw"] = record.get("gearRaw")
            observations = record.get("observations") or {}
            event["observations"] = {
                key: _observation(observations.get(key), base_ms)
                for key in ("PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION")
                if observations.get(key) is not None
            }
            event["effective"] = _effective(record.get("effective"))
        events.append(event)

    events.sort(key=lambda event: event["t"])
    fixture = {
        "meta": {
            "format": 1,
            "release": release,
            "sourceSha256": source_sha256,
            "sourceRecordCount": len(records),
            "sourceTypeCounts": dict(sorted(type_counts.items())),
            "eventCount": len(events),
            "sourceOldCompletionRecords": sum(
                1 for record in records if record.get("energyWindowCompleted") is True
            ),
        },
        "events": events,
    }
    assert_privacy_safe(fixture)
    return fixture


def assert_privacy_safe(fixture):
    if set(fixture) != {"meta", "events"}:
        raise ValueError("fixture root must contain only meta and events")
    if set(fixture["meta"]) - META_KEYS:
        raise ValueError("unknown metadata key")
    for event in fixture["events"]:
        unknown = set(event) - EVENT_KEYS
        if unknown:
            raise ValueError(f"unknown event keys: {sorted(unknown)}")
        for name, observation in (event.get("observations") or {}).items():
            if name not in {"PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION"}:
                raise ValueError(f"unknown observation: {name}")
            if set(observation) - OBSERVATION_KEYS:
                raise ValueError("unknown observation key")
        if isinstance(event.get("requested"), dict) and set(event["requested"]) - SETTINGS_KEYS:
            raise ValueError("unknown requested setting key")
        effective = event.get("effective")
        if isinstance(effective, dict):
            if set(effective) - EFFECTIVE_KEYS:
                raise ValueError("unknown effective setting key")
            requested = effective.get("requested")
            if isinstance(requested, dict) and set(requested) - SETTINGS_KEYS:
                raise ValueError("unknown nested requested setting key")
    text = json.dumps(fixture, sort_keys=True, separators=(",", ":"))
    if UUID_RE.search(text):
        raise ValueError("UUID-like identifier present")
    if SENSITIVE_VALUE_RE.search(text):
        raise ValueError("location/device/household-like value present")


def write_jsonl(fixture, stream):
    stream.write(json.dumps({"meta": fixture["meta"]}, sort_keys=True, separators=(",", ":")) + "\n")
    for event in fixture["events"]:
        stream.write(json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n")


def read_jsonl(stream):
    lines = [json.loads(line) for line in stream if line.strip()]
    if not lines or set(lines[0]) != {"meta"}:
        raise ValueError("first JSONL record must contain metadata")
    fixture = {"meta": lines[0]["meta"], "events": lines[1:]}
    assert_privacy_safe(fixture)
    return fixture


def read_archive(path):
    archive_path = pathlib.Path(path)
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    records = []
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            if not pathlib.PurePosixPath(info.filename).name.startswith("ev_") or not info.filename.endswith(".log"):
                continue
            with archive.open(info) as raw:
                for line in io.TextIOWrapper(raw, encoding="utf-8"):
                    if line.strip():
                        records.append(json.loads(line))
    records.sort(key=lambda record: record.get("elapsedMs", 0))
    return records, digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--release", default="0.1.501")
    parser.add_argument("--check", action="store_true", help="fail unless output already matches")
    args = parser.parse_args()
    records, digest = read_archive(args.archive)
    fixture = build_fixture(records, digest, args.release)
    rendered = io.StringIO()
    write_jsonl(fixture, rendered)
    data = rendered.getvalue()
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != data:
            raise SystemExit("fixture differs from sanitized archive output")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(data, encoding="utf-8")
    print(json.dumps(fixture["meta"], sort_keys=True))


if __name__ == "__main__":
    main()
