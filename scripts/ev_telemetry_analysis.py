#!/usr/bin/env python3
"""Parse and summarize mixed schema-1/schema-2 compact EV JSONL.

This is the offline analysis entry point for real ``ev_*.log`` files. Schema 1
forecasts may contain legacy initial/latest comparisons. Schema 2 forecasts are
raw, route-uncorrelated evidence and are never promoted to comparable forecasts.
"""

from __future__ import annotations

import argparse
import collections
import json
from typing import Any, Iterable, NamedTuple, TextIO


class ParsedRecord(NamedTuple):
    schema: int
    type: str
    raw: dict[str, Any]
    comparable_forecast: bool
    route_uncorrelated: bool


def parse_record(raw: dict[str, Any]) -> ParsedRecord:
    schema = raw.get("schema", 1)
    if schema not in (1, 2):
        raise ValueError(f"unsupported EV telemetry schema: {schema!r}")
    record_type = raw.get("type")
    if not isinstance(record_type, str) or not record_type:
        raise ValueError("EV telemetry record requires a string type")

    comparable = False
    uncorrelated = False
    if record_type in {"forecast", "forecast_empty", "forecast_uncorrelated"}:
        if schema == 1:
            comparable = (
                raw.get("initialArrivalWh") is not None
                and raw.get("latestArrivalWh") is not None
            )
        else:
            if raw.get("initialArrivalWh") is not None or raw.get("latestArrivalWh") is not None:
                raise ValueError("schema 2 forecast comparisons must remain null")
            basis = raw.get("forecastTimestampBasis")
            if basis not in (None, "callback_receipt_not_production"):
                raise ValueError("schema 2 forecast has an unknown timestamp basis")
            uncorrelated = raw.get("forecastCorrelation") in {
                "pre_route_epoch", "uncertain_no_protocol_route_identity"
            }
    return ParsedRecord(schema, record_type, raw, comparable, uncorrelated)


def read_records(stream: TextIO) -> Iterable[ParsedRecord]:
    for line_number, line in enumerate(stream, 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"line {line_number}: expected JSON object")
        yield parse_record(value)


def summarize(records: Iterable[ParsedRecord]) -> dict[str, Any]:
    records = list(records)
    schemas = collections.Counter(str(record.schema) for record in records)
    types = collections.Counter(record.type for record in records)
    return {
        "recordCount": len(records),
        "schemaCounts": dict(sorted(schemas.items())),
        "typeCounts": dict(sorted(types.items())),
        "comparableLegacyForecasts": sum(record.comparable_forecast for record in records),
        "uncorrelatedSchema2Forecasts": sum(record.route_uncorrelated for record in records),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=argparse.FileType("r", encoding="utf-8"))
    args = parser.parse_args()
    with args.jsonl:
        result = summarize(read_records(args.jsonl))
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
