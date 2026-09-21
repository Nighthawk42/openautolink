# EV Energy Model Tuning â€” Implementation Plan

Status: **HISTORICAL DESIGN â€” not the current implementation plan**

The settings UI and estimator were implemented, but an audit found their runtime
observer reachable only through an uncalled function. The schema assumptions
below also mix external and internal Maps models and mislabel road-load
coefficients. Do not activate this design or use its numeric defaults as a
protocol specification. See [the external-model contract audit](research/ev-external-model-contract.md).
Current staged work first adds byte-level contract fixtures and bounded payload
diagnostics without changing the transmitted model. Numerical corrections and
learning activation require a validated cross-vehicle conversion contract.

Goal: let users tune the `VehicleEnergyModel` (VEM, sensor type 23) we send to
Google Maps so battery-on-arrival / battery-on-return estimates better match
what AAOS Google Maps shows natively in the car.

See [docs/ev-energy-model-reverse-engineering.md](ev-energy-model-reverse-engineering.md)
for the full reverse-engineered protobuf and what Maps does with it.

---

## Background â€” what's actually different

### What AAOS Google Maps does natively (in the car)
- Has full access to a server-side, per-make/model/year EV vehicle profile
  (charge curves, aero coefficient, efficiency curves, real max DC power,
  battery thermal model). Google does not expose this profile via any public
  API.
- Combined with live VHAL data, it computes very accurate arrival % using
  many fields we don't currently populate.

### What we do today
File: [app/src/main/cpp/jni_session.cpp](../app/src/main/cpp/jni_session.cpp) â€” `sendEnergyModelSensor()`.

We build a **stripped** `VehicleEnergyModel` with only:
- `battery.max_capacity` â† `INFO_EV_BATTERY_CAPACITY` (VHAL)
- `battery.min_usable_capacity` â† `EV_BATTERY_LEVEL` (VHAL â€” Maps reads this as current SOC)
- `battery.reserve_energy` â† 5 % of capacity (hardcoded)
- `battery.max_charge_power_w` â† live `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` if > 0 else **150 000 W**
- `battery.max_discharge_power_w` â† **150 000 W** (hardcoded)
- `consumption.driving.rate` â† `(currentWh / rangeRemaining_m) Ã— 1000` â€” derived from the **car's own** RANGE_REMAINING estimate
- `consumption.auxiliary.rate` â† **2.0 Wh/km** (hardcoded)
- `consumption.aerodynamic.rate` â† **0.36** (hardcoded)
- `charging_prefs.mode` â† 1 (standard)
- All other fields (charge curves, efficiency, thermal, calibration, etc.) are unset.

### Why our number diverges from native AAOS Maps
1. Driving Wh/km is anchored to GM's RANGE_REMAINING which is conservative / seasonal.
2. Aero coefficient 0.36 is too high for a Blazer EV (~0.29 Cd).
3. Max charge power 150 kW is low (Blazer EV ~190 kW DCFC).
4. Reserve 5 % is arbitrary; Maps may treat it as unusable.
5. We don't send charging curves or efficiency points, so Maps can't model
   speed- or SOC-dependent behavior.

---

## Why not a Google Web API

Investigated and **rejected**:
- Google Maps **Routes API** EV mode requires us to *supply* `consumptionRateKwhPerKm` â€” there's no endpoint that returns Google's internal vehicle profile.
- No Maps SDK or Android Auto SDK exposes the per-vehicle EV model.
- The `VehicleEnergyForecast` Binder callback only reflects the model we sent.
- TOS / billing / quota / offline-use issues make per-session API calls a non-starter even if it existed.

Decision: rely on **EPA-prefilled defaults** + **user override sliders**.
The EPA table ships **bundled in the APK** so it works fully offline â€” the
head unit typically has no internet of its own (Maps' traffic is tunneled
through the phone, but the head unit itself usually sits on a private AP).
Any feature that requires a live network call must be **opt-in, cached, and
non-blocking**.

---

## Design

### Data model (DataStore preferences)

Add to [`AppPreferences`](../app/src/main/java/com/openautolink/app/data/AppPreferences.kt):

```kotlin
val EV_TUNING_ENABLED            = booleanPreferencesKey("ev_tuning_enabled")          // false = default calc
val EV_DRIVING_MODE              = stringPreferencesKey("ev_driving_mode")             // "derived" | "manual" | "multiplier"
val EV_DRIVING_WH_PER_KM         = intPreferencesKey("ev_driving_wh_per_km")           // used when mode=manual
val EV_DRIVING_MULTIPLIER_PCT    = intPreferencesKey("ev_driving_multiplier_pct")      // 50..150, used when mode=multiplier
val EV_AUX_WH_PER_KM_X10         = intPreferencesKey("ev_aux_wh_per_km_x10")           // *10 for 0.1 step
val EV_AERO_COEF_X100            = intPreferencesKey("ev_aero_coef_x100")              // *100 for 0.01 step
val EV_RESERVE_PCT               = intPreferencesKey("ev_reserve_pct")                 // 0..15
val EV_MAX_CHARGE_KW             = intPreferencesKey("ev_max_charge_kw")               // 50..350
val EV_MAX_DISCHARGE_KW          = intPreferencesKey("ev_max_discharge_kw")            // 50..300
```

Defaults reproduce **current** behavior so a fresh install behaves identically:

| Pref | Default |
|---|---|
| `EV_TUNING_ENABLED` | `false` |
| `EV_DRIVING_MODE` | `"derived"` |
| `EV_DRIVING_WH_PER_KM` | `160` |
| `EV_DRIVING_MULTIPLIER_PCT` | `100` |
| `EV_AUX_WH_PER_KM_X10` | `20` (= 2.0) |
| `EV_AERO_COEF_X100` | `36` (= 0.36) |
| `EV_RESERVE_PCT` | `5` |
| `EV_MAX_CHARGE_KW` | `150` |
| `EV_MAX_DISCHARGE_KW` | `150` |

### EPA prefill table (optional, phase 2)

Static asset `app/src/main/assets/ev_profiles.json` keyed by `make|model|year`:
```json
{
  "Chevrolet|C234|2024": {"combined_wh_per_km": 187, "max_charge_kw": 190},
  "Chevrolet|Bolt EUV|2023": {"combined_wh_per_km": 175, "max_charge_kw": 55},
  "...": {}
}
```
Source: EPA fueleconomy.gov API (combined kWh/100mi â†’ Wh/km). Generated
offline by a script in `scripts/build-ev-profiles.py`. When available for the
detected vehicle, used as the default for `EV_DRIVING_WH_PER_KM` and
`EV_MAX_CHARGE_KW` even when tuning is disabled â€” purely cosmetic prefill,
still respects the master toggle.

### UI â€” new screen

New file: `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelScreen.kt`

Reachable via a new row in [`SettingsScreen.kt`](../app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt)
under the existing vehicle/diagnostics section: **"EV Range Estimates â†’ Tweak"**.

Layout:

1. **Header card â€” explanation** (always visible, plain language):
   > Google Maps shows you a battery-percent estimate when you arrive at a
   > destination and when you get back home. Behind the scenes, Maps needs
   > to know how much energy your car uses per kilometer.
   >
   > **What Google does in the car natively:** Maps has a built-in profile
   > for many EVs that includes how the battery behaves at different
   > charge levels, how aerodynamic the car is, and how fast it can charge.
   > That profile is private â€” there's no way for an app to ask Google
   > what numbers it's using.
   >
   > **What OpenAutoLink does:** we send Maps a simpler model built from
   > what your car reports â€” its current battery level, its total capacity,
   > and the range estimate the dashboard shows. We add reasonable defaults
   > for the rest. This is usually close, but the dashboard's range tends
   > to be conservative, so Maps may show lower battery-on-arrival than
   > the native AAOS Maps in your car.
   >
   > If you want, you can override the values below and send Maps a more
   > optimistic (or pessimistic) profile until the numbers match what you
   > see side-by-side.

2. **Master toggle**: `Use default calculation` / `Customize`.
   When OFF, all sliders are disabled and grayed; we send today's behavior unchanged.

3. **Sliders / controls** (enabled only when toggle is on):
   - Driving consumption (segmented):
     - `Derived from car` (current)
     - `Multiplier`: 0.50Ã— â€“ 1.50Ã—, default 1.00Ã— (applied to derived)
     - `Manual`: 80â€“300 Wh/km, default 160
   - Auxiliary: 0.0â€“10.0 Wh/km (step 0.1)
   - Aerodynamic coefficient: 0.20â€“0.45 (step 0.01)
   - Reserve %: 0â€“15
   - Max charge power: 50â€“350 kW
   - Max discharge power: 50â€“300 kW

4. **Live readout card**: shows last sent VEM values (current Wh, capacity Wh, computed Wh/km, what Maps will compute as SOC %).

5. **"Send now" button**: forces an immediate VEM update so changes show up in Maps within seconds (bypass the 30 s throttle).

6. **"Reset to defaults" button**.

### ViewModel

New file: `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelViewModel.kt`. Standard `StateFlow` pattern, observes `AppPreferences`, exposes setters that call `appPreferences.set...()`.

### Plumbing â€” making the values flow into VEM

The sliders need to reach the JNI `sendEnergyModelSensor()`. Options:

**Option A (recommended) â€” pass overrides through the JNI call**

Extend the JNI signature:
```kotlin
fun sendEnergyModel(
    batteryLevelWh: Int, batteryCapacityWh: Int, rangeM: Int, chargeRateW: Int,
    drivingWhPerKm: Float,        // -1 = use derived
    auxWhPerKm: Float,
    aeroCoef: Float,
    reservePct: Float,
    maxChargeW: Int,
    maxDischargeW: Int,
)
```
- C++ uses the override values directly; if `drivingWhPerKm < 0`, falls back to the existing derived formula.
- `SessionManager` reads tuning prefs once at session start and on each VEM send (already throttled to 30 s).

**Option B â€” second JNI setter**: `setEnergyModelOverrides(...)` cached on the C++ side, applied inside `sendEnergyModelSensor`. Cleaner separation but more state.

Lean toward **A** â€” single source of truth, no hidden state in C++.

### "Send now" path

`EvEnergyModelViewModel.forceSend()` â†’
`SessionManager.forceSendEnergyModel()` â†’ reads latest VHAL snapshot from
`VehicleDataForwarderImpl.lastVehicleData` â†’ calls
`aasdkSession.sendEnergyModel(...)` directly, bypassing the 30 s throttle.

If session is not connected, show a snackbar: "Connect to phone first."

### Logging

Existing `DiagnosticLog.i("vem", ...)` line in `SessionManager` already prints what we send. Extend it to include override flags:
```
vem: level=53900Wh cap=85660Wh range=283000m charge=0W [tuning=ON drv=manual:165 aux=2.2 aero=0.30 res=4% chg=190kW]
```

---

## Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` | + 8 prefs, defaults, flows, setters |
| `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelScreen.kt` | **NEW** â€” Compose UI |
| `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelViewModel.kt` | **NEW** |
| `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` | + nav row "EV Range Estimates" |
| `app/src/main/java/com/openautolink/app/MainActivity.kt` (or nav graph) | + route `ev_energy_model` |
| `app/src/main/java/com/openautolink/app/session/SessionManager.kt` | read tuning prefs, pass to `sendEnergyModel`, add `forceSendEnergyModel()` |
| `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` | extend `sendEnergyModel(...)` signature |
| `app/src/main/cpp/aasdk_jni.cpp` | extend JNI binding |
| `app/src/main/cpp/jni_session.h` / `.cpp` | extend `sendEnergyModelSensor` to accept overrides; `< 0` â†’ derive |
| **Phase 2** `app/src/main/assets/ev_profiles.json` | EPA prefill table |
| **Phase 2** `scripts/build-ev-profiles.py` | offline generator from EPA data |

---

## Phasing

**Phase 1 â€” manual sliders (the actual ask)**
- Prefs + UI + plumbing through JNI.
- Master toggle off by default â†’ zero behavior change for existing users.
- Defaults under "Customize" reproduce current hardcoded values.

**Phase 2 â€” EPA prefill (nice-to-have)**
- Static JSON asset, **shipped inside the APK** â€” works fully offline. No
  network call at runtime. The head unit often has no internet
  (phone-hotspot mode pipes Maps' traffic *through* the phone, but the head
  unit itself is on a private AP); any feature that requires a live HTTP
  call would silently fail in the most common setup.
- On first session detect make/model/year, prefill `EV_DRIVING_WH_PER_KM`
  and `EV_MAX_CHARGE_KW` from the bundled JSON.
- Even when tuning is OFF, can optionally use prefilled `driving.rate`
  instead of the GM-range-derived value behind a sub-toggle "Use EPA
  combined as baseline".
- The EPA table itself is **regenerated offline** by
  `scripts/build-ev-profiles.py` and committed to the repo. New vehicles
  arrive via app updates, not runtime fetches.

**Optional Phase 2b â€” online refresh (gated, off by default)**
- If we ever add a runtime fetch (e.g. an opt-in "Update EV profile
  database" button), it must:
  - Be **off by default** and clearly labeled as requiring internet.
  - Probe connectivity first; fail silent-and-fast (no spinner > 2 s) when
    offline.
  - Cache the result so subsequent sessions work without internet.
  - Never block VEM sending â€” VEM always uses the last-known-good profile.
- Same rule applies to any future "ask Google for a route preview to
  cross-check arrival %" idea: opt-in, cached, never on the hot path.

**Phase 3 (later) â€” fuller VEM**
- Send simple synthetic charging curves (single point at max power) â€” quick win for charging-stop accuracy.
- Send efficiency.normal[] from EPA city/highway split.

---

## Open questions for implementation time

- Should **"Send now"** also be a long-press action on a session-state pill in the projection overlay, for quick A/B testing without leaving Maps? Probably yes, but ship the screen first.
- Do we hide the screen on non-EV vehicles (no `INFO_EV_BATTERY_CAPACITY`)? Probably **show but disable** with explanatory text â€” keeps the menu predictable.
- Persist a per-vehicle profile keyed by VIN (when readable) so two cars sharing one head-unit install (rare) keep separate tunings? Out of scope for v1.

---

## Acceptance test

1. Fresh install â†’ toggle OFF â†’ VEM bytes match the current build byte-for-byte.
2. Toggle ON, leave defaults â†’ VEM bytes still match (defaults equal hardcodes).
3. Set driving = manual 165 Wh/km, aero = 0.29, charge = 190 kW â†’ "Send now" â†’ log line shows new values; AAOS Maps arrival % updates within ~3 s.
4. Toggle OFF again â†’ log line returns to derived value.
5. Reconnect after car-off â†’ tuning persists; first VEM after reconnect uses tuned values.

---

## Phase 4 — Ambient-Temperature Compensation (planned)

**Status:** PLANNED. Default ON for both built-in (Derived / Multiplier / Manual / Learned / EPA) and Custom modes, with a single toggle to disable.

### Why we can do this well for GM EVs

GM blocks all HVAC properties (no AC compressor power, fan, setpoint, heater draw, seat heaters, defrost) — same restriction Google Maps faces on AAOS. But we **can** read `ENV_OUTSIDE_TEMPERATURE` (already subscribed in `VehicleDataForwarderImpl.kt`), and we know the GM Ultium battery + Blazer EV / Lyriq / Hummer EV / Equinox EV / Silverado EV chemistry well enough to publish a defensible curve.

### What native AAOS Google Maps almost certainly does (educated reconstruction)

From the decompiled VEM schema (`efficiency.normal[]`, `thermal.thermal_capacity`, `preconditioning_power_kW`, `consumption.auxiliary.rate`) Maps holds a temperature-vs-Wh/km efficiency curve per vehicle. It does **not** read live HVAC either — it relies on:
1. `ENV_OUTSIDE_TEMPERATURE` (or route-segment weather from Google's own backend).
2. Static profile efficiency curves (per make/model/year).
3. Speed × aero (covered by `consumption.aerodynamic.rate`).

A single ambient-temp efficiency multiplier captures most of what Maps does — the rest (per-segment weather, wind, sun load) is server-side and unreachable for us.

### GM Ultium thermal curve (initial coefficients)

Source: GM published EPA test data + InsideEVs / Out of Spec Reviews real-world cold/hot tests for Blazer EV, Lyriq, Hummer EV, Equinox EV. All Ultium-pack vehicles share roughly the same curve because they share the cell chemistry, pack thermal mass, and cabin-heat strategy (heat pump on Blazer/Equinox/Lyriq 2024+, resistive on Hummer/Silverado).

Reference temperature: **20 °C (68 °F)** = 1.00× multiplier.

| Ambient °C | Multiplier | Notes |
|---|---|---|
| -20 | 1.55 | severe cold; battery heater + cabin heater both running |
| -10 | 1.35 | cold; heat pump struggling on Blazer/Equinox |
| 0   | 1.20 | typical winter |
| 10  | 1.08 | mild cool |
| 20  | 1.00 | reference |
| 30  | 1.05 | AC moderate |
| 40  | 1.15 | hot; AC + battery cooling |
| 45  | 1.22 | extreme heat |

Linear interpolation between rows. Clamp to [1.00, 1.60].

Resistive-heat vehicles (Hummer EV, Silverado EV before 2025) get a steeper cold curve — bake into a per-vehicle override in `ev_profiles.json`:
`json
"GMC|Hummer EV|2024": { "wh_per_km": 290, "max_charge_kw": 350, "thermal_curve": "resistive" }
`
Two named curves shipped: `"heatpump"` (default) and `"resistive"`.

### Where it applies in the pipeline

The multiplier is applied to the **final `consumption.driving.rate`** sent to Maps:

`
effectiveWhPerKm = baseWhPerKm × thermalMultiplier(ambientTempC)
`

Where `baseWhPerKm` is whichever the user picked: Derived / Manual / Multiplier / Learned / EPA. So thermal compensation stacks cleanly on top of every existing mode.

### UI changes

In `EvEnergyModelScreen.kt`:

1. New section **"Ambient temperature compensation"** under the master tuning toggle but **above** the driving-mode picker — it applies regardless of mode.
2. Toggle: *Adjust for outside temperature (recommended)* — default **ON**.
3. When ON, show:
   - Live readout: `Outside: 4 °C ? +18 % (× 1.18)`
   - Curve preset dropdown: `GM Ultium (heat pump)` / `GM Ultium (resistive heat)` / `Custom`
4. When `Custom` is selected, expose six sliders for the multipliers at -20 / 0 / 20 / 30 / 40 / 45 °C (other points interpolated). Reset-to-default button.
5. When `ENV_OUTSIDE_TEMPERATURE` is unavailable, show `Outside: unknown — compensation disabled` and skip the multiplier (no fallback guess).

### Data model additions to `AppPreferences`

`kotlin
val EV_THERMAL_COMP_ENABLED = booleanPreferencesKey("ev_thermal_comp_enabled")    // default true
val EV_THERMAL_CURVE_PRESET = stringPreferencesKey("ev_thermal_curve_preset")     // "heatpump" | "resistive" | "custom"
val EV_THERMAL_CUSTOM_JSON  = stringPreferencesKey("ev_thermal_custom_json")      // JSON map {tempC: multiplier}

const val DEFAULT_EV_THERMAL_COMP_ENABLED = true
const val DEFAULT_EV_THERMAL_CURVE_PRESET = "heatpump"
const val DEFAULT_EV_THERMAL_CUSTOM_JSON  = ""
`

### Plumbing

Compute the multiplier in Kotlin (single source of truth), apply to the Wh/km value passed into the existing JNI call. **No JNI signature change required** — we already pass `drivingWhPerKm` as a float; we just multiply it before passing.

`kotlin
// SessionManager.kt — inside the energy-model send path
val baseWhPerKm = computeBaseWhPerKm(prefs, vd, learned)         // existing
val thermalMult = if (prefs.evThermalCompEnabled) {
    EvThermalCurve.multiplierFor(vd.ambientTempC, curve = prefs.evThermalCurvePreset)
} else 1.0f
val effectiveWhPerKm = baseWhPerKm * thermalMult
session.sendEnergyModel(... effectiveWhPerKm ...)
`

New helper file:
- `app/src/main/java/com/openautolink/app/data/EvThermalCurve.kt` (~80 lines, pure functions, unit-testable).
- `app/src/test/.../EvThermalCurveTest.kt` covering interpolation, clamping, missing-temp short-circuit.

### Logging

Extend the existing `vem:` log line:
`
vem: level=53900Wh cap=85660Wh range=283000m chg=0W [drv=learned:165 ambient=4°C therm=heatpump×1.14 ? eff=188 aux=2.0 aero=0.36 res=5% chg=150kW]
`

### Acceptance test

1. Toggle OFF (or unsupported vehicle) ? behavior identical to today, log shows `therm=off`.
2. Toggle ON, simulate `ENV_OUTSIDE_TEMPERATURE = 0` and Learned base 165 Wh/km ? effective ˜ 198 Wh/km; "Send Now" updates Maps within ~3 s.
3. Switch preset to `resistive` at -10 °C ? multiplier rises (= 1.45×) vs heatpump (~1.35×).
4. Custom curve with all multipliers = 1.00 ? effective rate equals base rate at every temperature.
5. Persists across car-off / reconnect; no thrash on temperature jitter (compensation re-applied at each VEM send, not stored separately).

### Why ON by default

Cold-weather range loss is the single biggest reason native AAOS Maps shows a different arrival % than ours. A reasonable compensation curve closes most of the gap with no user action and is genuinely beneficial — and the toggle is right there for users who'd rather see the raw model.

---

## Phase 5 — Companion Weather Enrichment (planned)

**Status:** PLANNED. Builds on Phase 4. Default ON when companion is connected; gracefully no-ops when offline. Runs entirely on the phone — head unit needs no internet.

### Why this exists

Phase 4 compensates for outside temperature using `ENV_OUTSIDE_TEMPERATURE` (current value, no forecast, no wind, no elevation). That closes most of the gap with native AAOS Maps but not all of it. Maps' server-side enrichment includes per-route-segment weather, wind, and elevation — data we already have a path for, because the phone-side **companion app** has reliable internet, GPS, and access to the active nav route via `INavigationState` callbacks.

This phase pushes that enrichment from the companion to the car over the existing control channel, with no new permissions and no head-unit network calls.

### What native AAOS Google Maps almost certainly does (educated reconstruction)

Maps' static VEM profile (`efficiency.normal[]`, `thermal.thermal_capacity`) is paired server-side with:

1. **Current weather at vehicle location** — for live consumption baseline.
2. **Per-segment forecast along the route** — temp, wind direction, precipitation, sun load.
3. **Elevation profile** — climbs cost ~3 kWh per 1 000 m gained; descents recover much less due to regen limits.
4. **Wind relative to heading** — 10 m/s headwind ˜ +10–15 % at highway speed.

Maps does **not** read live HVAC (same restriction we hit) — it relies on these signals + the static profile.

### What the companion can fetch trivially

| Data | Source | Cost / Friction |
|---|---|---|
| Current weather at GPS | **Open-Meteo** | free, no key, no signup, no rate limit (personal use) |
| Per-coordinate forecast (route) | Open-Meteo | same |
| Elevation profile | Open-Meteo elevation API | same |
| Wind direction / speed | Open-Meteo | same |
| Backup: phone ambient sensor | Android `Sensor.TYPE_AMBIENT_TEMPERATURE` | rare on modern phones; opportunistic |

US-only fallback if Open-Meteo TOS becomes an issue: **NOAA NWS API** (free, no key).

### Wire format — new control message

Add to `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` (and the companion side):

`kotlin
data class WeatherEnrichment(
    val timestampMs: Long,
    val ambientTempC: Float?,            // current at car location
    val windSpeedKph: Float?,
    val windHeadingDeg: Int?,            // 0..359, where wind is *coming from*
    val precipitationMmh: Float?,
    val cloudCoverPct: Int?,             // for sun-load proxy
    val routeSamples: List<RouteSample>? // null = no active nav
)
data class RouteSample(
    val etaMin: Int,                     // minutes from now (0 = current pos)
    val ambientTempC: Float,
    val elevationGainSinceLastM: Int,    // signed; cumulative since previous sample
    val headwindKph: Float                // signed: + = head, - = tail (along route bearing)
)
`

Triggers (companion ? car):
- Every **5 min** while session is connected and on highway speeds.
- **Immediately** when companion observes a new `INavigationState` route.
- **Immediately** when ambient delta > 3 °C from last send.
- Nothing when no nav is active and ambient hasn't moved (idle in driveway).

### How the car uses it

1. **Phase 4 thermal compensation** prefers `WeatherEnrichment.ambientTempC` over VHAL `ENV_OUTSIDE_TEMPERATURE` when present and < 30 min old. Falls back to VHAL ? falls back to disabled.
2. **New: route-aware effective Wh/km.** When `routeSamples` is present, the car app computes a route-weighted average:
   `
   effectiveRate = S (sample.weight × baseRate × thermal(sample.tempC) × windPenalty(sample.headwindKph) × elevationPenalty(sample.elevationGainSinceLastM)) / S weight
   `
   This single weighted average is what we send as `consumption.driving.rate`. Maps' own routing math then scales it across the route correctly.
3. **Wind penalty** (along-route headwind):
   `
   headwindKph -> multiplier
   -20 (tailwind)  0.95
     0             1.00
    20             1.10
    40             1.22
    60             1.35
   `
4. **Elevation penalty / credit** (per 100 m signed):
   `
   +100 m climb   +1.10 segment multiplier
   -100 m descent  0.95 (regen-limited credit)
   `

### Companion-side responsibilities

New file: `companion/src/main/java/com/openautolink/companion/weather/WeatherEnricher.kt`.

- Coroutine-based service, lifecycle tied to active control-channel connection.
- Throttle: max 1 fetch / 5 min, dedupe identical responses.
- Coalesces multiple triggers (e.g. nav-route + ambient delta within 30 s).
- Caches last response so transient cellular drops don't blank out the readout.
- Open-Meteo URL pattern (free, no key):
  `https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation,cloud_cover&hourly=temperature_2m,wind_speed_10m,wind_direction_10m&forecast_hours=2`
  `https://api.open-meteo.com/v1/elevation?latitude={lat1,lat2,…}&longitude={lon1,lon2,…}`
- All logic in the companion. The car app only deserializes and forwards into the existing energy-model pipeline.

### UI changes

In `EvEnergyModelScreen.kt` (car app), under the existing "Ambient temperature compensation" section:

1. Read-only readout: `Weather (companion): 4 °C, wind 12 kph @ 270°, route +180 m climb` — or `Weather (companion): unavailable — using ENV_OUTSIDE_TEMPERATURE` — or `Weather: VHAL only`.
2. Sub-toggle: **Route-aware enrichment (companion)** — default ON. Off = ignore `routeSamples`, still use current `ambientTempC`.
3. No sliders for wind/elevation curves — they're hardcoded in v1; can graduate to `Custom` later if anyone asks.

In the companion app settings (already has its own settings screen): a single toggle **"Send weather to car"** (default ON) and a small "last fetched: …" diagnostic line.

### Data model additions

`AppPreferences`:
`kotlin
val EV_WEATHER_ENRICHMENT_ENABLED = booleanPreferencesKey("ev_weather_enrichment_enabled")  // car-side master toggle, default true
val EV_WEATHER_ROUTE_AWARE_ENABLED = booleanPreferencesKey("ev_weather_route_aware_enabled") // default true

const val DEFAULT_EV_WEATHER_ENRICHMENT_ENABLED = true
const val DEFAULT_EV_WEATHER_ROUTE_AWARE_ENABLED = true
`

Companion preferences (mirror in companion's own DataStore): `COMPANION_WEATHER_ENABLED = true`.

### Privacy

- Open-Meteo receives `(lat, lon)` per request from the **phone**, no account / device ID. The car never makes a network request.
- We document this clearly in the privacy policy: "When the companion app is connected to your car, it may fetch local weather and elevation data from open-meteo.com to improve EV range estimates. No personal information is sent. You can disable this in companion settings."

### Risks / caveats

- **Open-Meteo TOS** — free for personal/non-commercial; OpenAutoLink is open source / non-commercial. Confirm before shipping. Fall-back: NOAA NWS API (US-only, also free, no key) selectable in companion settings.
- **Stale data** — car ignores enrichment > 30 min old; throttle prevents thrash.
- **Companion offline** — gracefully no-ops; `EvThermalCurve` uses VHAL data instead.
- **Battery / data on phone** — ~1 KB per Open-Meteo response, max 12 fetches/hr highway = ~12 KB/hr. Negligible.
- **Coordinate precision** — round to 0.1° (~11 km) to maximize Open-Meteo cache hits and minimize tracking footprint.

### Plumbing summary (no JNI changes)

`
[companion] WeatherEnricher ? ControlMessage.WeatherEnrichment (TCP)
                                          ?
[car app] ControlMessageDecoder ? SessionManager.lastWeatherEnrichment (StateFlow<WeatherEnrichment?>)
                                          ?
[car app] EvThermalCurve.multiplierFor(...) reads weather first, VHAL temp second
[car app] computeRouteAwareWhPerKm(...) consumes routeSamples when present
                                          ?
[car app] sendEnergyModel(... effectiveWhPerKm ...) — existing JNI signature unchanged
`

### Acceptance test

1. Companion offline / disabled ? car uses Phase 4 VHAL-temp compensation; log shows `weather=none`.
2. Companion online, idle (no nav) ? car uses `WeatherEnrichment.ambientTempC` for thermal compensation; log shows `weather=current`.
3. Companion online, nav active ? car uses route-weighted average; log shows `weather=route(N samples) eff=… therm=… wind=… elev=…`.
4. Companion connection drops mid-drive ? car keeps using last enrichment for 30 min, then falls back to VHAL.
5. Toggle `EV_WEATHER_ROUTE_AWARE_ENABLED` off ? only `ambientTempC` is used; `routeSamples` ignored.
6. Toggle `EV_WEATHER_ENRICHMENT_ENABLED` off ? companion-supplied weather ignored entirely; behavior identical to Phase 4 alone.

### Why this matches Maps as well as we plausibly can

After this phase, our energy model has:
- Live current SOC + capacity (VHAL).
- Tunable / EPA / learned base Wh/km.
- Ambient-temp thermal multiplier (GM Ultium curve).
- Route-segment temperature, wind-relative-to-heading, and elevation gain.
- Per-vehicle DCFC / charge-curve hints from `ev_profiles.json`.

The remaining delta to native Maps is mostly:
- Per-vehicle factory-tuned efficiency curves (private to Google).
- Cabin pre-conditioning prediction (we don't model it).
- Sun load / albedo (cloud-cover proxy gets us close).

These are diminishing-returns. Phase 5 is the realistic ceiling for a third-party app on AAOS without Google's private profile DB.

---

## Phase 6 — Phone Ambient Sensor as HVAC Proxy (planned)

**Status:** PLANNED. Educated guess. Default OFF until validated. Toggleable + tweakable from the car app.

### The premise

GM blocks every HVAC property on AAOS — we cannot read AC compressor draw, fan level, cabin setpoint, heater current, seat heaters, or defrost. But we **can** infer how hard the cabin HVAC is working by comparing two temperatures:

- **Outside ambient** — from companion-fetched weather (Phase 5) or VHAL `ENV_OUTSIDE_TEMPERATURE`.
- **Cabin temperature proxy** — from the phone's `Sensor.TYPE_AMBIENT_TEMPERATURE` reported by the companion.

If the phone is sitting in the cabin (it always is, when paired via OAL), and the phone's reading is consistently warmer or cooler than outside, the delta is roughly the cabin?outside gradient that HVAC is actively maintaining. Bigger gradient = more HVAC work = higher Wh/km.

### Why this is *educated* and not exact

1. **Sensor availability.** `TYPE_AMBIENT_TEMPERATURE` exists on a minority of Android phones. Pixel 7/8/9, Galaxy S24 Ultra (some), older Galaxy Note series — yes. OnePlus 13 (our reference test phone) — no, only `TYPE_TEMPERATURE` (deprecated, returns CPU temp). We must probe at companion startup and gracefully no-op when absent.
2. **Self-heating contamination.** Even when the sensor exists, Android's reading is biased by the phone's CPU/screen heat. The bias is fairly stable when the phone is idle on a cradle but spikes during heavy use (gaming, video record). Pixels generally model and subtract self-heating before reporting; some Samsung devices do not.
3. **Phone placement.** A phone on a sun-baked dashboard reads 60 °C; the cabin is 28 °C. We can't see placement directly. Mitigation: only trust the reading when it's *between* outside ambient and a plausible cabin range (e.g., outside 0 °C, phone 19 °C ? believable; outside 0 °C, phone 45 °C ? discard).
4. **Steady-state assumption.** HVAC inference only makes sense after the cabin has stabilized — the first 5 min of a cold start is HVAC working at full power but cabin temp still climbing. We need a settling window.

So this is a **bonus signal** that improves the estimate when conditions are favorable, not a replacement for Phase 4/5.

### How the math works

`
gradient = cabinProxyC - outsideC                 // signed
absGradient = abs(gradient)
hvacMultiplier = 1.0 + k * absGradient            // k tunable, default 0.005 (= 0.5 % per °C)
                                                  //   clamp to [1.00, 1.30]
`

Layered on top of the Phase 4 thermal multiplier:
`
effective = base × thermal(outsideC) × hvac(absGradient)
`

Reasoning:
- A 25 °C cabin?outside gradient (0 °C outside, 25 °C cabin) ? HVAC is doing real work ? ~ +12 % multiplier on top of thermal.
- A 5 °C gradient (15 °C outside, 20 °C cabin) ? mild AC fan only ? ~ +2 %.
- Outside == cabin ? 1.00× (no HVAC inference, thermal already captures pure ambient effect).

The `k = 0.005` constant is a starting guess from EPA cold-weather range tests on Ultium platform (where HVAC is the dominant variable cost above pure-ambient pack inefficiency). Exposed as a slider so users can tune.

### Sanity gates (silently disable inference when violated)

The car app applies the multiplier **only when all of these hold**:

1. `cabinProxyC` is between `outsideC - 5` and `outsideC + 50` (rejects sun-baked phones).
2. `cabinProxyC` is in the plausible cabin range `[-10, 50]`.
3. The phone reading hasn't moved more than 5 °C in the last 60 s (rejects active phone use spikes).
4. We've been driving (`PERF_VEHICLE_SPEED` available, > 0) for at least 5 min — settling window.
5. Companion `Sensor.TYPE_AMBIENT_TEMPERATURE` is actually present on this phone.

When any gate fails: `hvac multiplier = 1.0` and the readout shows `HVAC inference: skipped (reason)`.

### Companion responsibilities

New file: `companion/src/main/java/com/openautolink/companion/sensors/CabinTempSensor.kt`.

- Probe `Sensor.TYPE_AMBIENT_TEMPERATURE` at startup. If absent, register a flag and never claim cabin temperature again.
- Sample every 30 s, low-pass filter (3-sample median) to reject CPU spikes.
- Send via the existing `WeatherEnrichment` message — adds two fields:
  `kotlin
  data class WeatherEnrichment(
      ...,
      val phoneAmbientTempC: Float? = null,        // null = sensor absent / suppressed
      val phoneAmbientStableMs: Long = 0L          // how long it's been within ±0.5 °C
  )
  `
  No new wire message — extend the Phase 5 envelope.

The companion never makes its own decisions about HVAC inference; it just publishes the proxy reading. All policy lives in the car app.

### Car-app UI (single, cohesive home for the toggles)

Per the project's "minimal companion settings" principle, all of this is configured in the car app's existing `EvEnergyModelScreen`, under a new sub-section **"HVAC inference (experimental)"**:

1. Toggle: **Use phone's ambient sensor as cabin temperature proxy** — default **OFF** until validated.
2. Slider: **HVAC sensitivity** — 0 % to +1.5 % per °C of gradient, default 0.5 %.
3. Slider: **Maximum HVAC penalty** — 0 % to +50 %, default +30 %.
4. Read-only readout:
   - `Phone sensor: 21.4 °C (stable 8 min)`
   - `Outside: 4.0 °C`
   - `Gradient: 17.4 °C ? multiplier × 1.087`
   - or `Phone sensor: not available on this device`
   - or `Inference skipped: phone moved 8 °C in last 60 s`

The companion's own settings get **only** the bare minimum: a single line under its existing "Connection" panel reading `Cabin sensor: available / not available / suppressed` so users can see that the phone is contributing — no toggles, no sliders. If we ever need a permission flow (Android 14+ may gate body sensors; ambient temperature historically needs none, but verify), that prompt lives in the companion as a one-time banner.

### Data model additions

`AppPreferences` (car app):
`kotlin
val EV_HVAC_INFERENCE_ENABLED       = booleanPreferencesKey("ev_hvac_inference_enabled")
val EV_HVAC_SENSITIVITY_X1000       = intPreferencesKey("ev_hvac_sensitivity_x1000")  // 5 = 0.5%/°C
val EV_HVAC_MAX_PENALTY_PCT         = intPreferencesKey("ev_hvac_max_penalty_pct")    // 30

const val DEFAULT_EV_HVAC_INFERENCE_ENABLED = false
const val DEFAULT_EV_HVAC_SENSITIVITY_X1000 = 5
const val DEFAULT_EV_HVAC_MAX_PENALTY_PCT   = 30
`

Companion preferences: none. The companion sends what it has; the car decides whether to use it.

### Plumbing

`
[companion] CabinTempSensor ? WeatherEnrichment.phoneAmbientTempC (extends Phase 5 message)
[car app] HvacInferenceEvaluator (pure helper, ~60 lines, unit-testable):
              - reads gates
              - computes multiplier
              - returns (multiplier, status)
[car app] effective = base × thermal(outside) × hvac(gradient)
`

Single new helper: `app/src/main/java/com/openautolink/app/data/HvacInferenceEvaluator.kt`. No JNI changes, no new permissions.

### Logging

Extend the existing `vem:` log line:
`
vem: drv=learned:165 outside=4°C therm=×1.18 cabin=21°C(stable 8m) grad=17°C hvac=×1.09 ? eff=212
`
or
`
vem: drv=learned:165 outside=4°C therm=×1.18 hvac=skipped(noSensor) ? eff=195
`

### Risks / caveats

- **Sensor scarcity.** Most users will never see this active because their phone lacks the sensor. The feature is opt-in for that reason — users with supported phones get a small accuracy boost; users without get nothing extra (and no error).
- **Validation needed.** The 0.5 %/°C constant is a starting guess. Real-world testing on a Pixel + Blazer EV in cold/hot weather should refine it before we promote this past "experimental".
- **Potential anti-feature.** If a user's phone sensor is bad (poorly calibrated, heavily contaminated by self-heating, etc.) and they turn this on, range estimates get *worse*. The OFF default and clear "experimental" labeling protect against this.
- **No false confidence.** Readout always shows the gates' state so users can see when inference is silently disabled and why.

### Acceptance test

1. Phone without `TYPE_AMBIENT_TEMPERATURE` ? toggle is disabled with reason text; `effective` = thermal-only.
2. Phone with sensor, toggle OFF ? behavior identical to Phase 4/5 alone.
3. Toggle ON, sun-baked phone (sensor 50 °C, outside 30 °C, sun forecast clear) ? gate 1 trips ? multiplier = 1.00, readout says `skipped (sensor implausibly high)`.
4. Toggle ON, normal driving, gradient 15 °C ? multiplier ˜ 1.075; user can tune via sensitivity slider.
5. Phone usage spike (gradient jumps 8 °C in 30 s) ? gate 3 trips for 60 s ? multiplier reverts to 1.00, then re-engages once stable.

### Why this stays educated, not magical

Every step in this phase is an inference, not a measurement. We document that openly in the UI: the section is labeled **"experimental"**, the sensitivity is exposed, and the readout always tells users when inference is engaged vs skipped. If validation shows the model is just noise on real phones, the toggle stays OFF and the feature is a no-op — no harm done.
