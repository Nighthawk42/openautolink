#!/usr/bin/env python3
"""
build-ev-profiles.py — offline generator for app/src/main/assets/ev_profiles.json.

Pulls combined kWh/100mi from EPA fueleconomy.gov for EV models in the years
specified on the command line, converts to Wh/km, and merges with a manually
curated table of DCFC max charge power values (EPA does not publish DCFC
power; ev-database.org / manufacturer specs are the authoritative sources).

Usage:
    python scripts/build-ev-profiles.py --years 2022 2023 2024 2025
    python scripts/build-ev-profiles.py --years 2024 --offline   # skip EPA, use only the curated table

Notes:
- This script runs offline relative to the *app*. The JSON it produces is
  bundled in the APK so the head unit never needs internet.
- Re-run when EPA publishes new model years; commit the regenerated JSON.
- Keys are produced as "Make|Model|Year" using the exact INFO_MAKE / INFO_MODEL
  strings the GM AAOS VHAL reports (e.g. Chevrolet uses "C234" instead of
  "Blazer EV"). The curated table is the source of truth for those internal
  codes.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

EPA_BASE = "https://www.fueleconomy.gov/ws/rest"
TIMEOUT_S = 10

# Curated overlay. Manually maintained — values from manufacturer specs and
# ev-database.org. EPA does not publish DCFC power.
#
# Key format: "Make|Model|Year". For GM cars, INFO_MODEL contains the internal
# program code (e.g. "C234" for Blazer EV). Use the same string here, then add
# a friendly alias under "displayName" so the UI can show something human.
CURATED: dict[str, dict] = {
    # Chevrolet (GM AAOS reports internal program codes in INFO_MODEL)
    "Chevrolet|C234|2024":    {"displayName": "Blazer EV",   "drivingWhPerKm": 241, "maxChargeKw": 190},
    "Chevrolet|C234|2025":    {"displayName": "Blazer EV",   "drivingWhPerKm": 241, "maxChargeKw": 190},
    "Chevrolet|C234|2026":    {"displayName": "Blazer EV",   "drivingWhPerKm": 241, "maxChargeKw": 190},
    "Chevrolet|D2UB|2024":    {"displayName": "Equinox EV",  "drivingWhPerKm": 203, "maxChargeKw": 150},
    "Chevrolet|D2UB|2025":    {"displayName": "Equinox EV",  "drivingWhPerKm": 203, "maxChargeKw": 150},
    "Chevrolet|Bolt EUV|2023":{"displayName": "Bolt EUV",    "drivingWhPerKm": 176, "maxChargeKw": 55},
    "Chevrolet|Bolt EV|2023": {"displayName": "Bolt EV",     "drivingWhPerKm": 170, "maxChargeKw": 55},
    "GMC|T1ED|2024":          {"displayName": "Hummer EV",   "drivingWhPerKm": 410, "maxChargeKw": 350},
    "GMC|T1RV|2024":          {"displayName": "Sierra EV",   "drivingWhPerKm": 320, "maxChargeKw": 350},
    "Cadillac|C1XL|2024":     {"displayName": "Lyriq",       "drivingWhPerKm": 218, "maxChargeKw": 190},
    "Cadillac|T1ER|2025":     {"displayName": "Escalade IQ", "drivingWhPerKm": 350, "maxChargeKw": 350},
    # Ford
    "Ford|Mustang Mach-E|2023": {"drivingWhPerKm": 235, "maxChargeKw": 150},
    "Ford|Mustang Mach-E|2024": {"drivingWhPerKm": 230, "maxChargeKw": 150},
    "Ford|F-150 Lightning|2023":{"drivingWhPerKm": 305, "maxChargeKw": 155},
    "Ford|F-150 Lightning|2024":{"drivingWhPerKm": 305, "maxChargeKw": 155},
    # Tesla (almost never run AAOS, but we may see them via stock automotive build flavors)
    "Tesla|Model 3|2023":     {"drivingWhPerKm": 160, "maxChargeKw": 250},
    "Tesla|Model Y|2023":     {"drivingWhPerKm": 172, "maxChargeKw": 250},
    "Tesla|Model 3|2024":     {"drivingWhPerKm": 160, "maxChargeKw": 250},
    "Tesla|Model Y|2024":     {"drivingWhPerKm": 172, "maxChargeKw": 250},
    # Hyundai / Kia / Genesis (E-GMP)
    "Hyundai|IONIQ 5|2023":   {"drivingWhPerKm": 184, "maxChargeKw": 235},
    "Hyundai|IONIQ 5|2024":   {"drivingWhPerKm": 184, "maxChargeKw": 235},
    "Hyundai|IONIQ 6|2023":   {"drivingWhPerKm": 155, "maxChargeKw": 235},
    "Kia|EV6|2023":           {"drivingWhPerKm": 179, "maxChargeKw": 235},
    "Kia|EV6|2024":           {"drivingWhPerKm": 179, "maxChargeKw": 235},
    "Kia|EV9|2024":           {"drivingWhPerKm": 240, "maxChargeKw": 230},
    "Genesis|GV60|2023":      {"drivingWhPerKm": 195, "maxChargeKw": 235},
    # VW group
    "Volkswagen|ID.4|2023":   {"drivingWhPerKm": 214, "maxChargeKw": 135},
    "Volkswagen|ID.4|2024":   {"drivingWhPerKm": 200, "maxChargeKw": 175},
    "Audi|Q4 e-tron|2023":    {"drivingWhPerKm": 217, "maxChargeKw": 150},
    "Audi|e-tron GT|2023":    {"drivingWhPerKm": 220, "maxChargeKw": 270},
    # Rivian
    "Rivian|R1T|2023":        {"drivingWhPerKm": 300, "maxChargeKw": 220},
    "Rivian|R1S|2023":        {"drivingWhPerKm": 310, "maxChargeKw": 220},
    # BMW / Mercedes (limited AAOS overlap, but cheap to include)
    "BMW|i4|2023":            {"drivingWhPerKm": 180, "maxChargeKw": 200},
    "BMW|iX|2023":            {"drivingWhPerKm": 220, "maxChargeKw": 195},
    "Mercedes-Benz|EQE|2023": {"drivingWhPerKm": 200, "maxChargeKw": 170},
    "Mercedes-Benz|EQS|2023": {"drivingWhPerKm": 215, "maxChargeKw": 200},
    # Polestar / Volvo (AAOS-native ‑ very relevant)
    "Polestar|Polestar 2|2023":{"drivingWhPerKm": 215, "maxChargeKw": 155},
    "Polestar|Polestar 2|2024":{"drivingWhPerKm": 200, "maxChargeKw": 205},
    "Polestar|Polestar 3|2024":{"drivingWhPerKm": 240, "maxChargeKw": 250},
    "Polestar|Polestar 4|2024":{"drivingWhPerKm": 200, "maxChargeKw": 200},
    "Volvo|EX30|2024":        {"drivingWhPerKm": 175, "maxChargeKw": 153},
    "Volvo|EX90|2025":        {"drivingWhPerKm": 245, "maxChargeKw": 250},
    "Volvo|C40 Recharge|2023":{"drivingWhPerKm": 220, "maxChargeKw": 150},
    "Volvo|XC40 Recharge|2023":{"drivingWhPerKm": 225, "maxChargeKw": 150},
    # Honda / Acura (rebadged Ultium platform — share GM specs)
    "Honda|Prologue|2024":    {"drivingWhPerKm": 215, "maxChargeKw": 155},
    "Acura|ZDX|2024":         {"drivingWhPerKm": 220, "maxChargeKw": 190},
}


def fetch_xml(url: str) -> ET.Element | None:
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as resp:
            return ET.fromstring(resp.read())
    except (urllib.error.URLError, ET.ParseError, TimeoutError) as e:
        print(f"  WARN: {url}: {e}", file=sys.stderr)
        return None


def epa_lookup(year: int) -> dict[str, dict]:
    """Fetch all EVs (atvType=EV) for a given model year. Returns
    {"Make|Model|Year": {drivingWhPerKm: int}}."""
    out: dict[str, dict] = {}
    print(f"EPA: fetching makes for {year} …")
    makes_xml = fetch_xml(f"{EPA_BASE}/vehicle/menu/make?year={year}")
    if makes_xml is None:
        return out
    makes = [m.findtext("value") for m in makes_xml.findall("menuItem")]
    for make in makes:
        if not make:
            continue
        models_xml = fetch_xml(f"{EPA_BASE}/vehicle/menu/model?year={year}&make={make}")
        if models_xml is None:
            continue
        for m in models_xml.findall("menuItem"):
            model = m.findtext("value")
            if not model:
                continue
            opts_xml = fetch_xml(
                f"{EPA_BASE}/vehicle/menu/options?year={year}&make={make}&model={model}"
            )
            if opts_xml is None:
                continue
            for opt in opts_xml.findall("menuItem"):
                vid = opt.findtext("value")
                if not vid:
                    continue
                veh_xml = fetch_xml(f"{EPA_BASE}/vehicle/{vid}")
                if veh_xml is None:
                    continue
                atv = veh_xml.findtext("atvType") or ""
                if atv != "EV":
                    continue
                # combE = combined kWh/100mi; sometimes "0" for non-EV. Convert
                # to Wh/km: kWh/100mi × 1000 / 100 / 1.609 = kWh/100mi × 6.214.
                combe = veh_xml.findtext("combE")
                try:
                    kwh_per_100mi = float(combe or "0")
                except ValueError:
                    kwh_per_100mi = 0.0
                if not math.isfinite(kwh_per_100mi) or kwh_per_100mi <= 0:
                    continue
                # EPA combE is grid-side kWh/100mi, including charging losses;
                # this unit conversion alone does not make it battery-side or
                # turn it into a VEM road-load coefficient.
                converted = kwh_per_100mi * (1000 / (100 * 1.609344))
                if not math.isfinite(converted):
                    continue
                wh_per_km = round(converted)
                key = f"{make}|{model}|{year}"
                out[key] = {"drivingWhPerKm": wh_per_km}
                break  # one vehicle option is enough
    print(f"EPA: {year}: {len(out)} EV models")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--years", nargs="+", type=int, default=[2023, 2024, 2025])
    ap.add_argument("--out", type=Path,
                    default=Path("app/src/main/assets/ev_profiles.json"))
    ap.add_argument("--offline", action="store_true",
                    help="skip EPA fetch; produce JSON from CURATED only")
    args = ap.parse_args()

    profiles: dict[str, dict] = {}
    if not args.offline:
        for y in args.years:
            profiles.update(epa_lookup(y))

    # Curated overlay overrides EPA (and adds DCFC kW + display names).
    for k, v in CURATED.items():
        merged = dict(profiles.get(k, {}))
        merged.update(v)
        profiles[k] = merged

    args.out.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 1,
        "generated_by": "scripts/build-ev-profiles.py",
        "profiles": dict(sorted(profiles.items())),
    }
    args.out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.out} ({len(profiles)} profiles)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
