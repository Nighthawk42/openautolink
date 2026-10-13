# CarPlay vehicle-data contract qualification

This is a source/host-test qualification, not owner-phone or vehicle proof. The wire fixtures are synthetic shapes recovered from representative iPhone16,1 firmware **24A446**, not a capture of the owner's phone. Maps recognition, EV enrollment, consumption/charging model requirements, OEM GNSS output and visible routing remain unverified.

## Evidence and implemented boundaries

The outside-tree audit `/hermes_work/carplay-vehicle-protocol-deep-audit.md` pins disassembly and source comparisons in `/hermes_work/research/carplay-ios/analysis/24A446/vehicle-data-audit/`. In particular:

- `location-start.txt`, `location-wire-start.txt`, `location-filter-list.txt`: StartLocationInformation parameter **0 is a U32 global/minimum interval**, GGA/RMC selectors **1/2 are void**, and their U32 intervals are **0x8001/0x8002**. The production reporter now keeps independent sentence clocks. A present global interval bounds each selected sentence's minimum period; an absent global interval does not override a supplied per-sentence period. Missing intervals default to 1000 ms; accepted interval values are 1–60000 ms, with a **local 250-ms scheduling floor** to bound polling. The floor is a local resource policy, not a firmware scalar-width claim.
- `vehicle-start-wire.txt`: A100 includes void status-group selectors **3/11/21**, not empty-only starts and not scalar update selectors 22/23. Requests are intersected with the frozen supported groups. Empty starts retain the previous all-supported compatibility behavior. Unknown IDs, duplicates, malformed TLVs, oversized bodies and non-void selectors fail closed and revoke the old subscription. EV writes remain limited to one attempt per 30 seconds, including repeated starts and missing samples.
- `vehicle-update-*.txt` and `vehicle-status-schema.json`: verified update IDs/widths are retained: **3/11 U16 range**, **22/23 U32 current/usable-maximum energy**. A group-3 request does not authorize group-11 output; group 21 authorizes supported current/maximum fields. Codec units/profile eligibility are not expanded by the firmware width evidence.

GPS requests are capped at 128 bytes before parsing. Duplicate IDs, incorrect widths, out-of-bounds intervals and orphan per-sentence intervals are rejected precisely. These are bounded production admission policies, not a claim that every recovered sender encoding is accepted: interval IDs must carry U32 (including value 1); the sender's void-for-numeric-1 form is rejected as width, and 0x8000 is rejected as unsupported_id. Conflicting global/per-sentence values use the conservative max(global, sentence, 250-ms local floor), not a claimed firmware-mandated conflict rule. EV STOP→START preserves the exact link's last attempt timestamp: restart waits out any remaining 30-second resource budget, rather than allowing repeated stop/start to bypass it. The existing known PASCD selector 4 can be parsed as void but is never forwarded or advertised; PASCD/speed/gear semantics are not invented. Stop commands revoke even with malformed payloads. NMEA is still validated through the production cache and wire boundary, never synthesized from Android Location objects.

## USB ownership and capability coherence

One immutable capability snapshot feeds USB IdentificationInformation and its actual runtime reporter. A bounded, exact-CSM bootstrap owner retains only the latest command for each of the GPS and EV planes (two bounded copies, including invalid-start rejection sentinels). Identification, MFi and the post-authentication endpoint wait route only the four known vehicle start/stop IDs into that owner. Unknown messages retain the previous failure/endpoint handling. Deadlines are not extended by side requests.

The runtime authenticates the exact link before replay; there is no pre-authentication source activation or write, no extra CSM reader, no cross-link queue and no global/latest-session target. Stops replace older starts, payload mutation cannot change retained commands, and runtime cancellation is a nonblocking fence independent of transport-write retirement. The immutable offer does not grow when a later source appears, nor shrink because a separate late factory sampled stale data; every actual update still passes current source admission.

## Freshness: investigated, not loosened

`VehicleDataForwarderImpl` exports source timestamp, receipt timestamp, VHAL status, subscription activity and registration generation. `VehiclePropertyObservation` does **not** export the property's change mode or a verified on-change validity/refresh contract. Initial reads can contain old source timestamps even when their receipts are recent. Active registration and AVAILABLE alone do not establish a universal source-age exemption, and receipt refresh does not rejuvenate the source sample.

Accordingly the adapter retains the existing 30-second source **and** receipt-age checks rather than blindly increasing TTL. New bounded per-property receipts distinguish absent/fuel/metadata/generation/source/inactive/status/timestamp/source_age/receipt_age/bounds/inconsistent from available. These diagnostics can establish the next property's actual validity evidence without disclosing values. Current usable capacity remains distinct from nominal/new capacity; no nominal replacement, fabricated SOC, inferred charging state or connector inventory is added.

## GNSS activation

The Android LocationManager NMEA listener is a collection subscription, not an owned navigation request. The Android API requires GPS enabled and foreground location eligibility; the AOSP Android 15 `GnssNmeaProvider.registerWithService` calls `startNmeaMessageCollection`, not a LocationManager navigation request. See:

- https://developer.android.com/reference/android/location/LocationManager
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/services/core/java/com/android/server/location/gnss/GnssNmeaProvider.java

For subscribed CarPlay sources, `GnssForwarderImpl` now owns a 250-ms GPS request using its own exact listener and removes it on stop. The existing two-argument AA constructor retains its independent provider-request policy. A false NMEA registration result cannot claim an active source. Location callbacks only activate the engine: they never create or log NMEA/coordinates. Exact failed source retirement remains retryable while callbacks and reactivation are fenced. Host OS-boundary tests establish requested registration/removal, not OEM GNSS callbacks or phone consumption.

## Diagnostics and privacy

The production sanitizer accepts only full-string finite schemas for capability offers, request boundaries, property admission and bootstrap deferral. Existing successful after-write receipts retain transmission count and actual field presence. Per-key throttles and the app's bounded reporter/export queue remain in effect. Request diagnostics contain IDs, lengths, supported masks and admitted effective cadence only, never rejected scalar values, raw packets, coordinates, VIN, phone identity, authentication material or exception text. Oversized bootstrap bodies are represented by a bounded rejection sentinel rather than retained; later request `bytes=129` describes that sentinel, not the original oversized size. Source ages saturate at 99999999 ms; -1 means unavailable/future. Status -1 is unknown/non-allowlisted.

Tests cover the actual parser/reporter, adapter, identification transform, GNSS implementation, exact protocol client loops, bootstrap replay, production sanitizer/reporter and real LogUploadArchive. Source-wiring guards supplement, not replace, executable tests. Private/control-character suffixes and unknown diagnostic reasons must remain rejected. A completed transport write means **sent locally; Maps consumption unverified**.

Version allocation, signing, publication, PR/merge and affected-issue updates belong to the parent release controller. No device connection or deployment is part of this qualification.
