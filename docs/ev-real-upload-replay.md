# EV 0.1.501 real-upload replay gate

This gate is derived from the first private maintainer EV upload produced by car app **0.1.501**. The raw ZIP is not committed. Its byte identity is:

`SHA-256 a7ddb384fa9ac0e998238d1dbce371b5a0c93b3cfd0d07405712bac419f6662d`

The archive contained three `ev_*.log` members with **2,819 valid JSONL records** and no parse errors:

| Old output type | Count |
|---|---:|
| navigation | 2,146 |
| native_model | 350 |
| vehicle | 291 |
| forecast | 18 |
| forecast_empty | 2 |
| navigation_status | 3 |
| route_cancel_or_reset | 2 |
| gap | 3 |
| consent_start | 1 |
| session_start | 1 |
| requested_settings | 1 |
| upload_snapshot | 1 |

## What is committed

`scripts/ev_replay_fixture.py` reads an externally supplied ZIP, verifies/parses every EV JSONL record, and emits an allowlisted semantic-input fixture. The committed fixture is:

- `app/src/test/resources/ev-replay/0.1.501-a7ddb384-sanitized.jsonl`
- 2,468 replay events plus one metadata line
- 329,979 bytes
- fixture SHA-256 `4d08a86de9cbb86b55f5972873118fb17f331f8bf70a8a70d4e267c7743e2d3f`

The 351 excluded records are 350 native payload/output diagnostics and one upload snapshot. They are old outputs, not inputs to the repaired semantics.

The sanitizer replaces session UUIDs with `s1`/`s2`, converts elapsed times and observation times to relative values, and drops wall clocks, file names, boot/process/drive IDs, raw native lines, destinations, destination hashes, roads, coordinates, VINs, and unrelated vehicle fields. Its privacy gate rejects unknown event keys, UUID-shaped values, IP/MAC-like values, and location/device/household terms. The checked-in artifact has zero UUID matches, zero forbidden-key hits, and only the aliases `s1` and `s2` as session values.

## What the replay proves

`EvUploadReplayTest` does not validate ZIP shape alone. It converts old recorder output back into allowlisted semantic inputs, feeds those inputs through the current `EvTelemetryCore` and `EvTelemetryRecorder`, and asserts:

- the real shape has 1,073 zero-distance and 1,073 positive-distance navigation inputs;
- all 1,072 zeros following an explicit positive distance preserve both that distance and its original observation time (zero overwrites: 0; zero refreshes: 0);
- 15 distinct battery observation IDs yield 14 completed energy windows;
- the 14 source-ID intervals span 100,000–110,369 ms;
- all 14 completion records survive the recorder's normal five-second sampling gate;
- old 0.1.501 output contained 0 persisted completion records, keeping the old-output audit distinct from the current-code replay result;
- route-inactive/post-reset vehicle evidence and zero-distance partial navigation produce 0 arrival candidates;
- the one requested settings record says enabled learned mode, while all 291 vehicle records diagnose effective disabled/derived mode with an inactive, unstarted learner.

The private-archive test independently sanitizes the supplied ZIP in Kotlin, requires byte-for-byte-equivalent semantic events and metadata to the committed fixture, then runs the same current-code assertions. This detects fixture drift or a sanitizer that no longer represents the source archive.

## Commands

Create or verify the sanitized fixture locally:

```bash
python3 scripts/ev_replay_fixture.py /private/path/upload.zip \
  app/src/test/resources/ev-replay/0.1.501-a7ddb384-sanitized.jsonl
python3 scripts/ev_replay_fixture.py /private/path/upload.zip \
  app/src/test/resources/ev-replay/0.1.501-a7ddb384-sanitized.jsonl --check
python3 -m unittest scripts/test_ev_replay_fixture.py -v
```

Run the committed-fixture gate on the Android build host. Set `OAL_EV_REPLAY_ARCHIVE` to additionally run the private-archive equivalence gate:

```bash
flock /tmp/oal-ev-gradle.lock env \
  OAL_EV_REPLAY_ARCHIVE=/private/path/upload.zip \
  JAVA_HOME=/home/lance/.local/jdks/temurin-17 \
  ANDROID_HOME=/home/lance/.local/android-sdk \
  ./gradlew :app:testDebugUnitTest \
  --tests com.openautolink.app.diagnostics.EvUploadReplayTest
```

## Limits

0.1.501 recorded output snapshots approximately every five seconds; it did not persist every vehicle input delivered to the core. Replaying those snapshots at their original delivery times would invent 5+ second input gaps that were not present in the live process. The helper therefore uses two explicit lanes:

1. navigation/lifecycle inputs retain their real relative times; post-inactive vehicle evidence checks false-arrival behavior;
2. recorded vehicle values are replayed at a deterministic one-second delivery cadence while retaining their real battery source IDs and 100–110 second source cadence. This reconstructs no battery values and is limited to proving current on-change identity handling and persistence sampling behavior.

The replay cannot certify same-destination arrival accuracy, infer omitted per-tick speed values, prove Maps accepted a particular model revision, or replace an on-road capture from code built after 0.1.501.
