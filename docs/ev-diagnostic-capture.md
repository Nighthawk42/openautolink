# EV diagnostic capture for road testing

This build collects evidence to improve EV arrival estimates. It **does not change the vehicle energy model or enable learned-mode calibration**. A correct current battery display is not proof of an accurate arrival forecast.

## Normal use

1. Enable the existing **Always Log (maintainer)** option, or start file logging before the journey.
2. Start a Google Maps route before departure when practical. Drive normally; no screen interaction is required during the journey.
3. After reaching the destination and parking, press the existing **Upload Logs** button before turning the car off. Let the upload finish. Check the result message for the EV-file count and any omission or flush warning.
4. Repeat on the return journey. One ordinary journey first verifies data completeness; additional mixed-road and longer journeys then support analysis.

The car needs internet only for the manual upload. There is no automatic/background upload, new server endpoint or phone-app requirement for this capture. Existing owner authentication is reused.

## What is recorded

Dedicated `ev_*.log` files contain bounded structured records of observed energy/capacity, speed and integrated observed distance, signed net pack changes, range, gear/ignition, charging state, available temperatures, and input timestamps/status. Unknown or stale observations are retained with explicit provenance; gaps are not filled with invented driving distance.

Records also distinguish requested EV settings from effective runtime settings, retain actual native model payload/send outcomes and Maps forecasts, and identify route changes and parked near-arrival candidates. A candidate is not automatically a confirmed same-destination arrival. The existing dormant learner may correctly be reported as `inactive_not_initialized`; this build does not silently turn it on.

## Limits and privacy

- Collection follows explicit file-logging consent. Disabling capture rejects new observations; previously accepted entries may finish flushing to disk.
- The compact recorder has a bounded queue, small rotating files, retained-byte/file/age limits, and loss/error counters. It cannot guarantee recovery from process death, full storage or unavailable vehicle properties.
- Compact records exclude raw destinations, coordinates and VINs. Route identity is salted for the capture. **Existing general diagnostic/logcat files can still contain navigation and device details**; the complete upload remains private diagnostic data.
- EV files are prioritized across their retention window. Large or old general logs may be omitted; the ZIP manifest and result message report that. Growing files are bounded-prefix snapshots, not an atomic snapshot of every byte written afterward.
- Standard AAOS properties vary by vehicle. No fixed battery size, guessed temperature correction or one-vehicle calibration is applied.

## Interpretation

Compare initial and settled forecasts with energy at the same stop, excluding or labeling changed routes, charging, capture gaps and stale endpoints. Native transport completion is not proof Maps used that exact revision. The separate synthetic tablet lab is never part of the car build, and its fixtures must not be treated as real driving data.
