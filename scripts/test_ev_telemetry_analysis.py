import hashlib
import importlib.util
import io
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = pathlib.Path(__file__).with_name("ev_telemetry_analysis.py")
spec = importlib.util.spec_from_file_location("ev_telemetry_analysis", MODULE_PATH)
ev_telemetry_analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ev_telemetry_analysis)


class EvTelemetryAnalysisTest(unittest.TestCase):
    def test_actual_analysis_parser_accepts_mixed_schema_one_and_two(self):
        records = "\n".join((
            json.dumps({"schema": 1, "type": "forecast", "elapsedMs": 10,
                        "initialArrivalWh": 100, "latestArrivalWh": 90,
                        "receivedAtElapsedMs": 9}),
            json.dumps({"schema": 2, "type": "forecast", "elapsedMs": 20,
                        "arrivalWh": 80, "initialArrivalWh": None,
                        "latestArrivalWh": None,
                        "callbackReceivedAtElapsedMs": 19,
                        "forecastTimestampBasis": "callback_receipt_not_production",
                        "forecastCorrelation": "uncertain_no_protocol_route_identity"}),
        ))

        parsed = list(ev_telemetry_analysis.read_records(io.StringIO(records)))
        summary = ev_telemetry_analysis.summarize(parsed)

        self.assertEqual([1, 2], [record.schema for record in parsed])
        self.assertEqual(1, summary["schemaCounts"]["1"])
        self.assertEqual(1, summary["schemaCounts"]["2"])
        self.assertEqual(1, summary["comparableLegacyForecasts"])
        self.assertEqual(1, summary["uncorrelatedSchema2Forecasts"])

    def test_committed_fixture_matches_non_optional_provenance_manifest(self):
        manifest_path = ROOT / "app/src/test/resources/ev-replay/provenance.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        fixture = ROOT / manifest["fixturePath"]
        payload = fixture.read_bytes()
        first = json.loads(payload.splitlines()[0])["meta"]

        self.assertEqual("a7ddb384fa9ac0e998238d1dbce371b5a0c93b3cfd0d07405712bac419f6662d",
                         manifest["sourceArchiveSha256"])
        self.assertEqual(manifest["sourceArchiveSha256"], first["sourceSha256"])
        self.assertEqual(manifest["fixtureSha256"], hashlib.sha256(payload).hexdigest())
        self.assertEqual(manifest["fixtureBytes"], len(payload))
        self.assertEqual(manifest["eventCount"], first["eventCount"])


if __name__ == "__main__":
    unittest.main()
