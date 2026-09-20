# External sensor-23 VEM contract: Stage A fixtures

## Scope and non-goals

This is an **offline, standard-library Python wire inspector** and an independent
synthetic fixture suite. It describes a reviewed subset of the external Vehicle
Energy Model received by Maps, not OAL's reconstructed internal-model-shaped
`.proto`. It does not encode, send, patch or install a model. Stage A changes no
production native/Kotlin code, submodule, tuning preference, learned state or
transmitted bytes. It does not activate the dormant learning path.

Passing these tests means the local inspector agrees with manually specified
source-derived wire fixtures. **Google's decoder was not executed.** This does not
prove a live phone accepted, installed or used a payload, or improve a forecast.

## Evidence and its boundaries

The source audit records used for this contract are `protocol-audit.md` and
`fresh-protocol-audit.md` (2026-09-19 research bundle). Citations below identify
source versions/classes and physical file lines without embedding private logs,
routes, addresses or route hex.

### Direct historical PHONE Maps evidence

Phone Maps **26.21.01.916832833**, `defpackage/`:

- `pye.java:16–30`: sensor type 23 is parsed as external `cgsw`.
  `qjr.java:54–65` identifies the projected provider.
- `cgsw.java:8–18,36–37` and `cfbz.java:8–19,37–38` distinguish external and
  internal VEM layouts; `gbv.java:75–643` converts external to internal.
- `cgsy.java:8–19,38` describes external vehicle info. Current energy and capacity
  at fields 3/4 map through `gbv.java:100–122`; `gbv.java:666–678` copies the
  integer unchanged. `bboc.java:1401–1479` and `bbgj.java:36–37` establish
  current/capacity Wh and the optional display-percentage mapping.
- `cgsp.java:8–11,30` and `cgsa.java:8–9,28` specify three nested coefficient
  messages with two floats each. `gbv.java:206–253,681–693` copies them without
  reordering or scaling.
- `gbv.java:144–170` maps battery temperature to internal field 12, preserves
  power slots 9/10 and copies bool 11. External `cgsy` has no field 1;
  external `cgsw` has no top-level field 12. Internal preferences are constructed
  separately in `cuqj.java:260–282`.

This is direct projected-consumer evidence, but it is historical. It does not
certify the currently installed phone Maps build.

### Fresh AAOS corroboration, not a replacement phone decoder

AAOS Maps **26.08.310001.E**, `defpackage/`:

- `afeu.java:8–17,35` external VEM versus `aegz.java:8–18,36` internal VEM;
  external vehicle info `afew.java:8–18,33` versus internal `aehb.java:8–19,34`.
- `afec.java:7–8,23`: external integer with uncertainty is **int32 field 1,
  float field 2**. `afdq.java:7–8,23`: float pair at fields 1/2.
  `afel.java:8–10,25`: three nested float-pair road coefficients.
- `dkj.java:156–178,200–226,262–309` preserves current/capacity, temperature,
  power slots, heat-pump bool and coefficient ordering. `dkd.java:344–371`
  copies integer/float values unchanged. External power slots are **uint32**, not
  the internal/local reconstruction's int32. Ordinary positive watts share a
  varint representation; negative/out-of-range values do not share that guarantee.
- `hgc.java:100–138` explicitly warns about battery level in **Wh**.
  AAOS acceptance/history policy is not transplanted into this inspector as a
  phone policy.

The fresh audit also checks converter smali and descriptor counts. Those are
static source checks, not Google-decoder execution. Android Auto
**17.8.163804-release.daily** `gkf.java:154–165` forwards sensor-23 bytes opaquely;
that forwarding is not validation of field semantics.

### Named serving-schema corroboration

The car Templates Host's `com/google/geo/serving/proto/electricvehicle/` corpus
provides meaningful names where Maps' descriptors are obfuscated:

- `VehicleInfo.java:22–36`: current battery/capacity (3/4), battery temperature
  Celsius (12 internal), peak motor power W (9), max charging rate W (10),
  heat-pump-equipped (11), mass kg (8 internal), reference air density (6) and
  external temperature Celsius (7).
- `RoadLoadForceOrBuilder.java:6–17` and
  `FloatWithUncertaintyOrBuilder.java:6–13`: constant/linear/quadratic, each
  mean/standard deviation. The separate
  `com/google/common/logging/maps/mobile/navigation/LoggedVehicleEnergyModel.java:624–633`
  numbers the three coefficients 1/2/3. Its plain-float logging fields are **not**
  the nested input message to serialize.

These names plus cross-corpus conversion/shape agreement provide strong semantic
corroboration. They are not a recovered route evaluator.

## Explicit inspected contract

Paths are protobuf field numbers from the **raw VEM root**, not from a sensor
batch or GAL wrapper. Wire 0 = varint; 2 = length-delimited message; 5 = fixed32
little-endian IEEE-754 float. Intermediate containers are included in reports.

| Path | Wire/type | Interpretation |
|---|---|---|
| `1` | 2/message | Vehicle info |
| `1.3`, `1.4` | 2/message | Current battery energy / capacity, Wh |
| `1.3.1`, `1.4.1` | 0/int32 | Integer value, unchanged (not percentage or kWh) |
| `1.3.2`, `1.4.2` | 5/float | Uncertainty; absence is not measured zero uncertainty |
| `1.8` | 5/float | Battery temperature Celsius; **not nested reserve energy** |
| `1.9` | 0/uint32 | Peak motor power W; **not max charging** |
| `1.10` | 0/uint32 | Maximum charging rate W; **not max discharge** |
| `1.11` | 0/bool | Heat-pump equipped; **not regeneration capable** |
| `2` | 2/message | Road-load coefficient container |
| `2.1`, `2.2`, `2.3` | 2/message | Constant, linear, quadratic respectively |
| `2.{1,2,3}.1` | 5/float | Coefficient mean |
| `2.{1,2,3}.2` | 5/float | Coefficient standard deviation |
| `1.1` | Absent from external descriptor | Local config-ID interpretation unsupported |
| `12` | Absent from external descriptor | Local charging-preferences interpretation unsupported |

This is deliberately **not a complete schema**. Uninspected fields are unknown
to this tool, not necessarily absent from Google's schema. In particular,
external vehicle info has other fields (including 2, 5, 6, 7, 12) and the external
VEM has fields 3–10. Do not reinterpret them using the internal schema or invent
charging curves. External vehicle-info field 2 maps to internal mass field 8,
not reserve Wh; existing local efficiency labels at 6/7 are unsafe.

### Compatibility is not semantic correctness

Each occurrence is reported in wire order, with its complete `raw_hex`:

- `compatible`: reviewed number/wire and supported scalar domain match. This
  does not certify correct vehicle values or Maps acceptance. A legacy power
  value can be wire-compatible in the **wrong semantic slot**; the inspector
  cannot detect a swapped pair from bytes alone.
- `unknown`: not in the inspected subset. Known absent legacy `1.1`/`12` get an
  explicit unsupported-meaning note. Opaque length-delimited bytes are retained,
  never guessed to be a nested message.
- `wrong-wire`: known number with a different wire type, e.g. nested legacy
  reserve at `1.8`. Raw bytes are kept; no temperature value is invented.
- `invalid-value`: syntactically valid wire with an out-of-domain reviewed
  integer or nonfinite float. The raw value is retained (nonfinite floats as JSON
  strings). Physical plausibility, uncertainty sign, battery ratios and receiver
  policy are outside this wire check.

Duplicates are retained, **not** merged or collapsed using last-wins semantics.
`absent` lists missing reviewed field numbers in each visited known message;
it does not expand absent parents, apply defaults or promise receiver behavior.
A wrong-wire occurrence is present but incompatible, not absent. Containers may
be wire-compatible while their child fields are not. Inspect the full report.

## Safe omission versus invented meaning

A future corrected encoder should omit unknown optional capabilities, not claim
that every vehicle has a heat pump. Explicit false is distinct from unknown;
the golden fixture deliberately omits `1.11`. Do not turn missing temperature or
uncertainty into measured zero. Do not relocate reserve Wh to mass or battery
temperature, preserve a guessed config ID as a receiver revision key, or assume
top-level field 12 controls projected charging preferences.

“Omit safely” here means **avoid making unsupported claims on the wire**, not
that Google's fallback behavior is known or that an empty model is useful.
Required information and receiver defaults remain a separate acceptance gate.
Stage A does not change existing sender omissions or legacy assignments.

## Units and the no-3× gate

Current/capacity are Wh and are copied unchanged. Road-load coefficients are
copied unchanged in both adapters. Their constant/linear/quadratic structure
supports a polynomial road-load interpretation, not three additive Wh/km terms.
**Exact force scale and speed coordinate remain unresolved**, as do drivetrain
losses, accessories, grade/regen handling, missing-model defaults and route
sampling. No recovered code here proves the route equation or an efficiency.
Neither demo coefficients nor historical forecast ratios justify a universal
**3× or 3.6× patch**. Do not activate learning to feed more accurate Wh/km into an
unproven force coefficient. Resolve dimensions and validate the phone consumer
before a separately reviewed adaptation or calibration change.

## Run locally

From the repository root, with Python 3 and no Android SDK/protobuf dependency:

```sh
python3 app/src/test/native/ev_energy_model_contract_test.py -v
python3 tools/ev_vem_contract.py --hex '0a05450000c841'
python3 tools/ev_vem_contract.py --file /path/to/raw-vem.bin
```

The example is synthetic battery temperature 25 C, not a captured route. Input
must be the raw VEM only. JSON goes to stdout. Exit 0 means inspected with no
known wire/domain conflicts (**unknown fields still allowed**); exit 1 means
wrong-wire/invalid-value findings; exit 2 means malformed/unsupported encoding,
resource limit, input/CLI or file error. No files or network state are modified.
Reports preserve raw payload bytes; treat reports from real captures as private.

The API `inspect_vem(bytes, max_bytes=65536, max_fields=1024, max_depth=8)` has
hard ceilings; callers can only lower them. Limits count all visited occurrences
including containers. Varints have a ten-byte/uint64 ceiling, field numbers are
bounded, and every fixed-width/length-delimited read is checked before slicing.
Unknown data is opaque. Groups (wire 3/4) are explicitly unsupported, even though
they can be valid protobuf; wire 6/7 is rejected. Binary file reads stop after
65,537 bytes. This is a bounded diagnostic parser, not a general protobuf runtime.

## Independent fixture coverage and acceptance gate

The hand-specified golden literal in the test does not import or invoke an OAL
encoder. It contains current 50,000 Wh and capacity 80,000 Wh, uncertainties
10/20, 25 C, deliberately unequal synthetic motor/charge powers 300/150 W,
three coefficient mean/stddev pairs (100/1, 2/0.25, 0.5/0.125), and unknown heat
pump via omission. Small synthetic power values are diagnostic, not fleet defaults.

Other literals exercise legacy config/reserve/preferences, explicit false/true
heat pump, duplicate fields, opaque unknown wire 0/1/2/5, signed/zero current
energy, uint32 boundaries, nonfinite floats, truncation, varint overflow, invalid
field numbers/wires and reduced byte/field/depth budgets. Subprocess tests check
real CLI hex/file paths and exit codes. No encoder round-trip is used as an oracle.

Further gates remain: real phone receiver/decoder validation; proven units and
fallbacks; a separately reviewed production schema/sender repair; then learning
wiring and held-out road validation across vehicle/capacity/capability cases.
These fixtures are not evidence of a forecast-accuracy fix.
