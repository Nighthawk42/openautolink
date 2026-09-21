import importlib.util
import io
import json
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("ev_replay_fixture.py")
spec = importlib.util.spec_from_file_location("ev_replay_fixture", MODULE_PATH)
ev_replay_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ev_replay_fixture)


class EvReplayFixtureTest(unittest.TestCase):
    def test_sanitizes_old_output_into_new_semantic_inputs(self):
        records = [
            {
                "type": "session_start", "elapsedMs": 10_000, "wallMs": 99_000,
                "session": "550e8400-e29b-41d4-a716-446655440000",
            },
            {
                "type": "navigation", "elapsedMs": 10_100,
                "remainingM": 321, "etaSec": 45, "destinationHash": "forbidden",
            },
            {
                "type": "vehicle", "elapsedMs": 10_200,
                "speedKmh": 12.5, "batteryWh": 50_000.0, "gearRaw": 8,
                "observations": {
                    "PERF_VEHICLE_SPEED": {
                        "source": "vhal", "timestampElapsedNanos": 10_150_000_000,
                        "receivedElapsedMs": 10_160, "status": 0,
                    },
                    "EV_BATTERY_LEVEL": {
                        "source": "vhal", "timestampElapsedNanos": 9_000_000_000,
                        "receivedElapsedMs": 9_010, "status": 0,
                    },
                },
                "effective": {"requested": {"enabled": True, "mode": "learned"},
                              "enabled": False, "mode": "derived",
                              "observerStarted": False,
                              "learnerState": "inactive_not_initialized"},
            },
            {"type": "native_model", "elapsedMs": 10_300, "line": "private payload"},
        ]

        fixture = ev_replay_fixture.build_fixture(
            records, source_sha256="a" * 64, release="0.1.501"
        )

        self.assertEqual(4, fixture["meta"]["sourceRecordCount"])
        self.assertEqual(3, len(fixture["events"]))
        self.assertEqual("s1", fixture["events"][0]["session"])
        self.assertEqual(0, fixture["events"][0]["t"])
        self.assertEqual(100, fixture["events"][1]["t"])
        self.assertNotIn("destinationHash", json.dumps(fixture))
        self.assertNotIn("wallMs", json.dumps(fixture))
        self.assertNotIn("550e8400", json.dumps(fixture))
        ev_replay_fixture.assert_privacy_safe(fixture)

    def test_privacy_gate_rejects_unknown_keys_and_identifier_values(self):
        safe = {
            "meta": {"format": 1, "release": "0.1.501", "sourceSha256": "a" * 64,
                     "sourceRecordCount": 1, "sourceTypeCounts": {"navigation": 1},
                     "eventCount": 1, "sourceOldCompletionRecords": 0},
            "events": [{"type": "navigation", "t": 0, "distanceM": 10, "etaSec": 1}],
        }
        ev_replay_fixture.assert_privacy_safe(safe)
        for bad_event in (
            {"type": "navigation", "t": 0, "road": "Main Street"},
            {"type": "event", "t": 0, "session": "550e8400-e29b-41d4-a716-446655440000"},
        ):
            bad = json.loads(json.dumps(safe))
            bad["events"] = [bad_event]
            with self.assertRaises(ValueError):
                ev_replay_fixture.assert_privacy_safe(bad)

    def test_jsonl_round_trip_is_deterministic(self):
        fixture = {
            "meta": {"format": 1, "release": "0.1.501", "sourceSha256": "b" * 64,
                     "sourceRecordCount": 0, "sourceTypeCounts": {}, "eventCount": 0,
                     "sourceOldCompletionRecords": 0},
            "events": [],
        }
        first = io.StringIO()
        ev_replay_fixture.write_jsonl(fixture, first)
        second = io.StringIO()
        ev_replay_fixture.write_jsonl(ev_replay_fixture.read_jsonl(io.StringIO(first.getvalue())), second)
        self.assertEqual(first.getvalue(), second.getvalue())


if __name__ == "__main__":
    unittest.main()
