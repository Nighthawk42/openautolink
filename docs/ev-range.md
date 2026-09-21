# EV range estimates

> **Accuracy limitation:** Current battery reporting and arrival prediction are
> separate. An audit found that saved EV tuning settings are not bound to the
> active direct-AA session, so selecting Learned does not prove learning is
> running. It also found incorrect meanings in the reconstructed energy-model
> fields. The modes below describe the intended UI behavior, not verified active
> calibration. Do not compensate with a guessed multiplier or rely on these
> estimates to decide whether a charging stop is safe. Use the vehicle's native
> range/charging guidance. See [the external-model contract audit](research/ev-external-model-contract.md).

OpenAutoLink forwards vehicle energy information through Android Auto's standard vehicle energy model. Google Maps can then display battery-aware destination estimates and charging information using data from the car rather than treating the phone as disconnected from the vehicle.

Native AAOS Google Maps has private, factory-tuned vehicle profiles that third-party apps cannot read. OpenAutoLink builds the next-best model from accessible VHAL data plus a bundled vehicle profile database.

## Open the EV settings

In the car app, open **Settings → EV**.

## Detected vehicle profile

OpenAutoLink reads available make, model, and year information and compares it with its bundled EV profiles. A matched profile can apply baseline efficiency and charging-power values.

The bundled database is a starting point, not an OEM calibration. Confirm that the detected vehicle is correct before applying it.

## Driving-rate modes

### Derived

Uses current battery energy divided by the vehicle's remaining-range estimate. This is the default and tracks the dashboard's own estimate.

### Multiplier

Scales the derived rate from 0.50× through 1.50×. Use it when Maps is consistently optimistic or pessimistic while the underlying vehicle estimate remains useful.

### Manual

Sets a fixed driving consumption rate from 80–300 Wh/km. Use it when the vehicle's remaining-range estimate is unavailable or unsuitable.

### Learned

Learns a rolling consumption rate from battery change and distance integrated from vehicle speed. GM blocks odometer access, so OpenAutoLink integrates accessible speed data instead.

The estimator:

- persists per-vehicle state across reconnects;
- skips samples while charging or regenerating;
- rejects implausible outliers;
- resets continuity after long gaps;
- can be cleared with **Reset learned rate**.

Learned mode needs enough real driving before its estimate becomes useful.

## Other controls

Depending on release, the EV page includes:

- auxiliary load;
- aerodynamic coefficient;
- reserve percentage;
- maximum charge/discharge power;
- live battery, range, charging-power, derived-rate, and effective-rate readouts;
- **Send Now** to publish an updated model immediately.

## Profile database refresh

Profiles ship inside the APK and work offline. Optional refresh downloads an updated profile database, validates it, and caches it locally. It is off by default; OpenAutoLink does not require internet for the bundled profiles.

## What proves it works

Do not treat a sent sensor message as final proof. Confirm the phone-side result:

- Google Maps shows the vehicle battery percentage or battery-aware destination estimate;
- the value changes plausibly with live vehicle state;
- route estimates respond to a deliberate model adjustment or **Send Now** action.

## Limitations

- OpenAutoLink cannot read Google/OEM private per-vehicle calibration.
- Battery and range properties vary across AAOS vendors.
- A matched profile does not prove the vehicle exposes all required VHAL values.
- Learned estimates depend on the accuracy and cadence of battery and speed data.
- Weather, elevation, HVAC, towing, tires, and driving style can move actual consumption outside a baseline profile.

Use the model as a tunable navigation input, not a replacement for the vehicle's own low-battery warnings or safe charging judgment.
