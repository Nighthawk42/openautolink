# EV Energy Model Reverse Engineering

## Status: Historical reconstruction — arrival accuracy not established

The original tests below established current SOC display and the presence of
arrival forecasts, not their accuracy over completed routes. A later external
versus internal schema audit found that several reconstructed names and field
types below are incorrect: the three consumption slots correspond to road-load
coefficients; reserve, regeneration, configuration and power labels are not safe
implementation guidance. This document retains historical findings, not a current
wire contract. See [the external-model contract audit](research/ev-external-model-contract.md)
before changing the sender. Exact coefficient units and conversion to battery
energy remain a release gate; do not infer a universal multiplier from one car.

EV routing with battery-on-arrival estimates is fully functional via the AA protocol.
When connected to an EV with VHAL data, the bridge sends a VehicleEnergyModel protobuf
through undocumented sensor type 23. Google Maps on the phone processes it and shows
battery percentage remaining at each destination in search results and during navigation.

**Verified:**
- April 2026 on AAOS emulator: Maps shows battery estimates
- April 2026 on real Chevrolet Blazer EV (C234 2024): Maps shows **current battery**
  readings matching the car's SOC; this does not validate arrival predictions. Car VHAL provides EV_BATTERY_LEVEL,
  INFO_EV_BATTERY_CAPACITY, RANGE_REMAINING — all subscribed and reading live values.

### How It Works

1. Service discovery advertises sensor types 23, 25, 26 + FUEL_TYPE_ELECTRIC + EV connectors
2. Phone AA app requests sensor type 23 during session setup
3. App reads VHAL: `EV_BATTERY_LEVEL`, `INFO_EV_BATTERY_CAPACITY`, `RANGE_REMAINING`
4. Builds `VehicleEnergyModel` protobuf with battery capacity, current level, consumption rate
5. Sends as sensor type 23 in `SensorBatch` ΓÇö phone passes through to GMS ΓåÆ Maps
6. Maps computes battery-on-arrival estimates using the energy model + route data

### Real Car VHAL Data (Chevrolet Blazer EV C234 2024)

| VHAL Property | Value | Status |
|---------------|-------|--------|
| `INFO_MAKE` | Chevrolet | read OK |
| `INFO_MODEL` | C234 | read OK |
| `INFO_MODEL_YEAR` | 2024 | read OK |
| `INFO_FUEL_TYPE` | [10] (ELECTRIC) | read OK |
| `INFO_EV_CONNECTOR_TYPE` | [1, 5] (J1772 + CCS1) | read OK |
| `INFO_EV_BATTERY_CAPACITY` | 85,660 Wh | subscribed |
| `EV_BATTERY_LEVEL` | ~53,900 Wh (varies) | subscribed |
| `RANGE_REMAINING` | ~283,000 m (varies) | subscribed |
| `EV_CHARGE_PORT_OPEN` | Yes/No | subscribed |
| `EV_CHARGE_PORT_CONNECTED` | Yes/No | subscribed |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | 0.0 W (parked) | subscribed |
| `PERF_VEHICLE_SPEED` | ΓÇö | CAR_SPEED permission not granted |
| `PERF_ENGINE_RPM` | ΓÇö | CAR_SPEED permission not granted |
| `PERF_ODOMETER` | ΓÇö | CAR_MILEAGE permission not granted |
| `PARKING_BRAKE_AUTO_APPLY` | ΓÇö | not exposed by HAL |

### Known Issues and Fixes

**VEM Timing Bug (fixed in v0.1.103):** Early VHAL property callbacks (GEAR,
PARKING_BRAKE) triggered `VehicleData` messages before EV battery values were
populated. The 30-second VEM throttle then blocked the later message that had
complete data. Fixed by adding a `vemEverSent` flag that bypasses the throttle
for the initial send ΓÇö VEM is sent immediately when all three values
(capacity, level, range) first become available.

**trackedPropertyIds Bug (fixed):** Initial VHAL property reads were silently
dropped because `trackedPropertyIds.add(propId)` happened AFTER the
`handleChangeEvent()` call which checks `if (propertyId !in trackedPropertyIds)`.
STATIC properties like `INFO_EV_BATTERY_CAPACITY` never got callback updates,
so they were permanently missing. Fixed by moving `trackedPropertyIds.add()`
before the initial read.

**min_usable_capacity Misinterpretation (fixed in v0.1.109):** Maps was showing ~94%
battery instead of the real ~74%. Root cause found by tracing through decompiled Maps
code (`rah.java`, method `m33791O`): Maps reads `BatteryConfig.min_usable_capacity` as
the **current battery level**, NOT as a static minimum usable capacity floor. It reads
`max_capacity` as total capacity, then computes SOC = `min_usable_capacity / max_capacity`.
We were setting `min_usable_capacity = capacityWh * 0.95` (a constant), so Maps always
computed ~95% regardless of actual charge. Fixed by setting `min_usable_capacity = currentWh`
(the live `EV_BATTERY_LEVEL` value).

**Gross vs Usable Capacity (investigated, reverted):** Initially suspected that the
car's dashboard used a separate "usable" capacity from `EV_CURRENT_BATTERY_CAPACITY`,
and that using the gross `INFO_EV_BATTERY_CAPACITY` produced wrong percentages. Applied
an 88% correction factor as fallback. Real-car testing (April 2026) proved this wrong:
the Blazer EV dashboard computes SOC directly from `EV_BATTERY_LEVEL / INFO_EV_BATTERY_CAPACITY`
(e.g., 53,900 / 85,660 = 62%, matching the dashboard). `EV_CURRENT_BATTERY_CAPACITY` is not
exposed by GM's HAL. Using the raw gross capacity produces matching percentages in Maps.
If `EV_CURRENT_BATTERY_CAPACITY` becomes available on other vehicles, we still prefer it.

Additionally, arrive/return estimates showed the same percentage (no route energy
deduction). Added missing VEM fields discovered in the AAOS Maps decompile
(`gzd.m22124j`): `max_charge_power_w`, `max_discharge_power_w`, and
`consumption.aerodynamic.rate` — the server-side EV routing computation likely
needs these to produce accurate battery-on-arrival estimates.

```java
// From rah.m33791O() — how Maps constructs BatteryLevel from VEM:
//   batteryLevelWh  = aeahVar.f10387d  → min_usable_capacity.watt_hours
//   batteryCapacityWh = aeahVar.f10388e → max_capacity.watt_hours
//   result = new qjp(batteryLevelWh, batteryCapacityWh, ...)
```

**Remote Debugging:** VEM send status is logged via `DiagnosticLog.i("vem", ...)`
which flows through the bridge control channel to the relay's journal. Check with:
```
ssh root@<SBC_IP> "journalctl -u openautolink --no-pager --since '5 min ago' | grep vem"
```

### Key Files

| File | Role |
|------|------|
| `external/opencardev-aasdk/protobuf/.../SensorType.proto` | Sensor type enum (23-26 added) |
| `external/opencardev-aasdk/protobuf/.../SensorBatch.proto` | SensorBatch with field 23-26 |
| `external/opencardev-aasdk/protobuf/.../VehicleEnergyModel.proto` | Reconstructed VEM proto |
| `bridge/openautolink/headless/src/live_session.cpp` | `sendVehicleEnergyModel()` + SDR + VEM parsing |
| `bridge/openautolink/headless/include/openautolink/live_session.hpp` | Sensor handler + VEM throttle state |
| `app/src/main/java/.../ControlMessageSerializer.kt` | Serializes EV battery fields to bridge JSON |
| `app/src/main/java/.../VehicleDataForwarderImpl.kt` | VHAL property reading (trackedIds fix) |

---

## Overview

Google Maps on AAOS and via Android Auto can show battery-on-arrival estimates for EV routing. This document captures the reverse-engineered protobuf schemas and data flow from decompiling both the AAOS Google Maps APK and the Android Auto phone APK.

## Data Flow

```
                    AA Protocol (sensor channel)
AAOS Head Unit  ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ>  Phone AA App
  reads VHAL:                                           Γöé
  - EV_BATTERY_LEVEL                                    Γöé pass-through
  - INFO_EV_BATTERY_CAPACITY                            Γöé (opaque bytes)
  - RANGE_REMAINING                                     Γû╝
  - EV_CHARGE_PORT_OPEN                              GMS Car Service
  - CarInfoManager                                      Γöé
    (make/model/year/                                   Γöé
     fuel types/connectors)                             Γû╝
                                                   Google Maps (phone)
                                                     sends back:
                                                   VehicleEnergyForecast
                                                     - energyAtNextStop
                                                     - distanceToEmpty
                                                     - chargingStops
```

## Undocumented AA Sensor Types

The AA protocol has sensor types beyond the publicly documented 1-22. Found in the decompiled AA APK (`wyb.java`):

| Sensor ID | Internal Name | Proto Wrapper | Purpose |
|-----------|--------------|---------------|---------|
| 23 | `SENSOR_VEHICLE_ENERGY_MODEL_DATA` | `wrc` (empty msg, pass-through) | Vehicle energy model protobuf |
| 24 | `SENSOR_TRAILER_DATA` | `wyq` | Trailer info |
| 25 | `SENSOR_RAW_VEHICLE_ENERGY_MODEL` | `wxn` (bytes field) | Same as 23, raw bytes shortcut |
| 26 | `SENSOR_RAW_EV_TRIP_SETTINGS` | `wxl` (bytes field) | EV trip configuration |

### Wire Format

Sensor 23 and 25 produce identical output. From `gdz.java`:
```java
case 23:
    if (aaxjVar instanceof wrc) {
        bytes = ((wrc) aaxjVar).serialize();     // unknown-field pass-through
    } else if (aaxjVar instanceof wxn) {
        bytes = ((wxn) aaxjVar).rawBytes;         // direct bytes
    }
    return new CarSensorEvent(23, timestamp, new float[0], bytes);
case 25:
    return new CarSensorEvent(25, timestamp, new float[0], ((wxl) aaxjVar).rawBytes);
```

### Phone-Side Processing

The AA app on the phone (`ioj.java`) deserializes sensor data, stores it, and forwards to GMS via `CarSensorEvent`. The energy model bytes are **opaque** ΓÇö the AA app does not parse them, just passes through.

### Gating

From `lnj.java` (GhNavDataManager):
```
"Can't send vehicle energy forecast without enhanced nav metadata enabled."
```
The `enhanced nav metadata` feature flag must be enabled during session setup.

## Reconstructed Protobuf Schema

### VehicleEnergyModel (aeaf) ΓÇö Sensor Type 23 Payload

This is the protobuf serialized into the bytes field of sensor type 23/25.

```protobuf
// Reconstructed from AAOS Google Maps decompile (aeaf and sub-messages)
// Field numbers and types decoded from protobuf-lite descriptor strings

message VehicleEnergyModel {
  BatteryConfig battery = 1;                    // aeah
  EnergyConsumption consumption = 2;            // aeaa
  // field 3 unused
  VehicleSpecs specs = 4;                       // adzv
  // field 5 unused
  repeated ChargingCurvePoint curves_ac = 6;    // adzh (AC charging curve)
  repeated ChargingCurvePoint curves_dc = 7;    // adzh (DC charging curve)
  EnergyEfficiency efficiency = 8;              // adze
  ThermalModel thermal = 9;                     // adzf
  ConnectorConfig connectors = 10;              // adzj
  WheelConfig wheels = 11;                      // adza
  ChargingPrefs charging_prefs = 12;            // adzw
  CalibrationData calibration = 13;             // aeae
}

message BatteryConfig {                         // aeah
  int64 config_id = 1;                          // identifier/version
  // field 2 unused
  EnergyValue min_usable_capacity = 3;          // e.g. {wh=64740}
  EnergyValue max_capacity = 4;                 // e.g. {wh=78000}
  EnergyDisplayUnit display_unit = 5;           // enum
  float charge_efficiency = 6;                  // 0.0-1.0
  float discharge_efficiency = 7;               // 0.0-1.0
  EnergyValue reserve_energy = 8;               // e.g. {wh=2154}
  int32 max_charge_power_w = 9;                 // e.g. 170000 (170kW)
  int32 max_discharge_power_w = 10;             // e.g. 155000 (155kW)
  bool regen_braking_capable = 11;
  float preconditioning_power_kw = 12;
  repeated BatterySegment segments = 13;        // aeag (SoC curve segments)
}

message EnergyConsumption {                     // aeaa
  EnergyRate driving = 1;                       // e.g. {rate=138.38} Wh/km
  EnergyRate auxiliary = 2;                     // e.g. {rate=2.18} Wh/km
  EnergyRate aerodynamic = 3;                   // e.g. {rate=0.3617} drag coeff
}

message EnergyValue {                           // adzs
  int32 watt_hours = 1;                         // energy in Wh
  float display_value = 2;                      // formatted for display
}

message EnergyRate {                            // adzr
  float rate = 1;                               // Wh/km or coefficient
  float uncertainty = 2;                        // confidence/error margin
}

message ChargingCurvePoint {                    // adzh
  float charge_rate_kw = 1;                     // power at this SoC
  EnergyValue soc_point_1 = 2;                  // SoC breakpoint
  EnergyValue soc_point_2 = 3;                  // SoC breakpoint
  EnergyValue soc_point_3 = 4;                  // SoC breakpoint
}

message ChargingPrefs {                         // adzw
  string provider_package = 1;                  // e.g. "com.google.android.apps.maps"
  bool user_has_modified = 2;
  ChargingMode mode = 3;                        // enum: 0=unknown, 1=standard
}

message ThermalModel {                          // adzf
  int32 thermal_zone = 1;
  EnergyValue thermal_capacity = 2;             // adzs
}

message ConnectorConfig {                       // adzj
  repeated ConnectorEntry entries = 7;          // adzu (starts at field 7)
  int32 default_connector = 8;
}

message VehicleSpecs {                          // adzv
  repeated FuelTypeEntry fuel_types = 1;        // adzy
  repeated ConnectorTypeEntry ev_connectors = 2;// adzz
}

message EnergyEfficiency {                      // adze
  repeated EfficiencyPoint normal = 4;          // adzc (starts at field 4)
  repeated EfficiencyPoint eco = 5;             // adzd
}

message WheelConfig {                           // adza
  repeated WheelEntry wheels = 4;               // adzt (starts at field 4)
}

message CalibrationData {                       // aeae
  repeated CalibrationPoint point_set_1 = 1;    // aead
  int32 calibration_version_1 = 2;
  repeated CalibrationPoint point_set_2 = 3;    // aeab
  int32 calibration_version_2 = 4;
  repeated CalibrationPoint point_set_3 = 5;    // aeac
}
```

### RawVehicleEnergyModel Wrapper (wxn) ΓÇö Sensor Type 25

```protobuf
message RawVehicleEnergyModel {
  bytes energy_model_payload = 1;   // serialized VehicleEnergyModel
}
```

### RawEvTripSettings (wxl) ΓÇö Sensor Type 26

```protobuf
message RawEvTripSettings {
  bytes trip_settings_payload = 1;  // serialized trip settings proto
}
```

## VehicleEnergyForecast ΓÇö What Maps Sends Back

After receiving the energy model and computing routes, Maps sends `VehicleEnergyForecast` back through the `INavigationStateCallback` Binder interface (transaction code 4).

```
// From com.google.android.apps.auto.sdk.nav.state

VehicleEnergyForecast {
  EnergyAtDistance energyAtNextStop;     // {distanceMeters, arrivalBatteryEnergyWh, timeToArrivalSeconds}
  EnergyAtDistance distanceToEmpty;      // when battery reaches 0
  int forecastQuality;                  // confidence level
  ChargingStationDetails nextChargingStop; // {minDepartureEnergyWh, maxRatedPowerWatts, estimatedChargingTimeSeconds}
  List<StopDetails> stopDetails;        // per-stop {expectedArrivalEnergy, chargingInfo}
  List<DataAuthorization> dataAuthorizations; // consent tracking
}
```

## Known Values from AAOS Maps (gzd.java)

These were found hardcoded in the AAOS Maps decompile for demo/testing:

| Field | Value | Unit |
|-------|-------|------|
| min_usable_capacity | 64,740 | Wh |
| max_capacity | 78,000 | Wh |
| reserve_energy | 2,154 | Wh |
| max_charge_power | 170,000 | W (170 kW) |
| max_discharge_power | 155,000 | W (155 kW) |
| driving_consumption | 138.38 | Wh/km |
| auxiliary_consumption | 2.18 | Wh/km |
| aerodynamic_coefficient | 0.3617 | - |

## VHAL Properties We Already Read

Our app (`VehicleDataForwarderImpl.kt`) already subscribes to:

| VHAL Property | ID | Permission | Maps To |
|---------------|-----|-----------|---------|
| `EV_BATTERY_LEVEL` | 0x11600309 | CAR_ENERGY | Current battery Wh |
| `INFO_EV_BATTERY_CAPACITY` | 0x11600106 | CAR_INFO | Max capacity Wh |
| `RANGE_REMAINING` | 0x11600308 | CAR_ENERGY | Range in meters |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | 0x1160030C | CAR_ENERGY | Instantaneous pack flow in mW (converted to W by OAL) |
| `EV_BATTERY_AVERAGE_TEMPERATURE` | 0x1160030E | CAR_ENERGY | Pack temp ┬░C |
| `INFO_FUEL_TYPE` | (runtime) | CAR_INFO | Fuel type list |
| `INFO_EV_CONNECTOR_TYPE` | (runtime) | CAR_INFO | Connector type list |

## Implementation Plan

### Status: COMPLETE

All steps implemented and verified:

1. **aasdk extended** ΓÇö sensor types 23-26 in `SensorType.proto`, `SensorBatch.proto`
2. **VehicleEnergyModel protobuf** built from VHAL data:
   - `battery.max_capacity` = `INFO_EV_BATTERY_CAPACITY`
   - `battery.min_usable_capacity` = `EV_BATTERY_LEVEL` (Maps reads this as current SOC)
   - `battery.reserve_energy` = 5% of max
   - `consumption.driving.rate` = `EV_BATTERY_LEVEL / RANGE_REMAINING * 1000` (Wh/km)
3. **Sent as sensor 23** in the SensorBatch during AA session ΓÇö WORKING
4. **Maps shows battery-on-arrival** ΓÇö VERIFIED on emulator and real car

### Feature Negotiation

No special feature negotiation required beyond:
- Advertising sensor type 23 in the SensorSourceService configuration
- Declaring `FUEL_TYPE_ELECTRIC` and EV connector types in the service discovery
- The phone automatically requests sensor 23 when these are advertised

The `ENHANCED_NAVIGATION_METADATA` capability flag (ID 20) is NOT required for
VEM to work. The phone requests sensor 23 independently.

### VEM Timing

The VEM is sent when all three VHAL values are available:
- First send: immediately (bypasses 30s throttle via `vemEverSent` flag)
- Subsequent sends: every 30 seconds while AA is connected
- The `vemEverSent` flag was needed because early VHAL callbacks (GEAR, PARKING_BRAKE)
  trigger VehicleData before EV values are populated

## Source Files Referenced

### AAOS Google Maps APK (Gmaps_teardown/java_src/)
- `p000/ghs.java` ΓÇö EmbeddedCarInfo (reads CarPropertyManager)
- `p000/gzd.java` ΓÇö VehicleEnergyModel builder (hardcoded demo values)
- `p000/aeaf.java` ΓÇö VehicleEnergyModel protobuf
- `p000/aeah.java` ΓÇö BatteryConfig protobuf
- `p000/aeaa.java` ΓÇö EnergyConsumption protobuf
- `p000/adzs.java` ΓÇö EnergyValue protobuf
- `p000/adzr.java` ΓÇö EnergyRate protobuf
- `p000/adzw.java` ΓÇö ChargingPrefs protobuf
- `p000/adzh.java` ΓÇö ChargingCurvePoint protobuf
- `p000/fgn.java` ΓÇö Powertrain detection (BEV/PHEV/ICE)

### Android Auto Phone APK (Gmaps_teardown/aa_apk_src/)
- `p000/wyb.java` ΓÇö Sensor type enum (23-26 undocumented)
- `p000/irc.java` ΓÇö Sensor typeΓåÆproto class mapping
- `p000/ioj.java` ΓÇö Sensor data parser (deserialize + forward)
- `p000/gdz.java` ΓÇö CarSensorEvent builder (bytes extraction)
- `p000/wrc.java` ΓÇö VEHICLE_ENERGY_MODEL_DATA proto (empty pass-through)
- `p000/wxn.java` ΓÇö RAW_VEHICLE_ENERGY_MODEL proto (bytes wrapper)
- `p000/wxl.java` ΓÇö RAW_EV_TRIP_SETTINGS proto (bytes wrapper)
- `p000/lnj.java` ΓÇö GhNavDataManager (sends VehicleEnergyForecast)
- `p000/lnl.java` ΓÇö INavigationStateCallback impl (setVehicleEnergyForecast)
- `com/google/android/apps/auto/sdk/nav/state/VehicleEnergyForecast.java`
- `com/google/android/apps/auto/sdk/nav/state/EnergyAtDistance.java`
- `com/google/android/apps/auto/sdk/nav/state/ChargingStationDetails.java`
- `com/google/android/apps/auto/sdk/nav/state/StopDetails.java`

- `p000/rah.java` — BatteryLevel construction from VEM (m33791O: min_usable_capacity → current SOC)
- `p000/qjp.java` — BatteryLevel data class (batteryLevelWh, batteryCapacityWh)

### Protobuf-lite Type Encoding (for decoding descriptors)

Type IDs in info string (GROUP is skipped):
```
0=double, 1=float, 2=int64, 3=uint64, 4=int32,
5=fixed64, 6=fixed32, 7=bool, 8=string,
9=message, 10=bytes, 11=uint32, 12=enum
18+ = repeated versions (type + 18)
```

High byte of type_info char: `0x10` = has-bit presence word 1, `0x14/0x15` etc = different has-bit offsets. `0x08` flag in high byte = closed enum (needs default value in Objects[]).

## Implementation Details

### VEM Construction (live_session.cpp)

The `sendVehicleEnergyModel(capacityWh, currentWh, rangeM)` method builds the protobuf:

```
battery.max_capacity.watt_hours = capacityWh          // EV_CURRENT_BATTERY_CAPACITY preferred, fallback INFO_EV_BATTERY_CAPACITY
battery.min_usable_capacity.watt_hours = currentWh     // from EV_BATTERY_LEVEL — Maps reads this as current SOC!
battery.reserve_energy.watt_hours = capacityWh * 0.05
battery.regen_braking_capable = true
battery.max_charge_power_w = curated vehicle profile or 150000 // instantaneous pack flow is not capability
battery.max_discharge_power_w = 150000
consumption.driving.rate = (currentWh / rangeM) * 1000  // Wh/km from car's own range estimate
consumption.auxiliary.rate = 0.0                         // range-derived driving rate already includes current auxiliaries
consumption.aerodynamic.rate = 0.0                       // only nonzero in an independent tuned/profile model
charging_prefs.mode = 1                                  // standard
```

> **Confirmed (April 2026):** On the Blazer EV, the dashboard computes SOC as
> `EV_BATTERY_LEVEL / INFO_EV_BATTERY_CAPACITY` (e.g., 53,900 / 85,660 = 62%).
> `EV_CURRENT_BATTERY_CAPACITY` is not exposed by GM's HAL. Using the raw gross
> capacity produces matching percentages. No 88% correction factor is needed.

> **Critical insight:** Despite the proto field name `min_usable_capacity`, Maps uses this
> as the **current battery level in Wh**. This was discovered by tracing `rah.m33791O()` in
> the decompiled Maps APK, which constructs `qjp(batteryLevelWh=min_usable_capacity,
> batteryCapacityWh=max_capacity)`. Setting this to a static value (e.g. 95% of max)
> makes Maps think the battery is always near-full.

The energy consumption rate is derived from the car's own range estimate rather than hardcoded,
so it automatically reflects the car's driving conditions, temperature, and driving style.

### Throttling and Timing

VEM send timing:
- **First send:** Immediate when all three VHAL values are available (bypasses throttle)
- **Subsequent sends:** At most once every 30 seconds
- The `vem_ever_sent_` flag in `LiveAasdkSession` (live_session.cpp) controls this behavior
- VEM is only sent when `isVemRequested()` is true (phone requested sensor 23 in `onSensorStartRequest`)

**Timing issue (fixed):** Early VHAL callbacks fire before EV properties are populated.
The first `VehicleData` message has null cap/level/range. Without `vemEverSent`, the
30s throttle would block the later message that has complete data.

### Permission Requirements

On the real car (GM AAOS), `android.car.permission.CAR_ENERGY` is pre-granted to system apps.
On the AAOS emulator, this permission must be manually granted via `pm grant` (signature-level).

### Bug Fix: trackedPropertyIds ordering

`VehicleDataForwarderImpl.kt` had a bug where initial VHAL property reads were silently
dropped because `trackedPropertyIds.add(propId)` happened AFTER the initial `handleChangeEvent()`
call, which checks `if (propertyId !in trackedPropertyIds) return`. This was invisible for
subscribable properties (callbacks re-populate values) but caused STATIC properties like
`INFO_EV_BATTERY_CAPACITY` to never appear in `currentValues`. Fixed by moving
`trackedPropertyIds.add(propId)` before the initial read.

### Future Work

- Add charging curve data from VHAL (if exposed by HAL) for more accurate charging stop estimates
- Investigate sensor type 26 (EV_TRIP_SETTINGS) for trip-specific battery targets
- Periodic VEM updates during driving to reflect changing consumption patterns
- Consider sending fuel data (sensor 6) in parallel for hybrid vehicles
- Investigate `rah.m33790N()` interpolation logic for edge cases (very low/high SOC)
