# BoschData

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://gist.githubusercontent.com/anpaiss/78cf5eb76ad50594402b9778008a00a8/raw/BoschData-card-dark.svg">
  <img alt="downloads and latest release" src="https://gist.githubusercontent.com/anpaiss/78cf5eb76ad50594402b9778008a00a8/raw/BoschData-card-light.svg">
</picture>

Your Bosch Smart System e-bike on the Hammerhead Karoo, over Bluetooth. No
extra hardware: the Karoo talks to the bike through the Bosch **Live Data
Interface**, the interface Bosch published in May 2026 for accessories.

## What you get

**Six data fields**, to put on any ride page:

| Field | Shows |
|---|---|
| Bike battery | The battery % exactly as the bike displays it ("charging" when plugged in) |
| Rider power (Bosch) | Your watts, from the Bosch torque sensor |
| Bike speed (Bosch) | Wheel speed, km/h |
| Cadence (Bosch) | Your cadence, rpm |
| Bike odometer | Total km of the bike |
| Bike light | Light ON/OFF and the ambient brightness the bike measures |

All of them show `--` while the bike is off.

**The bike as a Karoo sensor.** BoschData offers a "Bosch eBike" sensor with
the rider power (on by default), and optionally cadence and speed. Pair it once
from the Karoo sensors menu and the Karoo treats it like a power meter: power
in the FIT file, on Strava, in the training metrics and zones.

**The battery in the FIT file.** The battery % is written in every ride file
as the developer field `bosch_battery`, so every ride gives you its discharge
curve point by point (on by default, switch in the app).

**For other extensions.** The fields are streams any Karoo extension can read;
[boschRange](https://github.com/anpaiss/BetterETA/tree/v2-dashboard/boschrange)
takes the battery from `bike_battery` to predict the charge at the end of the
route without asking you anything.

## Requirements

- Karoo 3 (the Karoo acts as a Bluetooth accessory; the Karoo 2 cannot).
- A Bosch Smart System bike with the Live Data Interface: control unit
  firmware v19 (May 2026) or later, drive units CX Gen4, CX Gen5, SX. Check
  with the Bosch Flow app that "Add component → Accessories" exists.

## Install

1. Download `BoschData.apk` from the [latest release](https://github.com/anpaiss/BoschData/releases/latest)
   and sideload it from the Hammerhead companion app, or install it from the
   Karoo extensions library once it is listed there.
2. Open BoschData on the Karoo and press **GRANT BLUETOOTH** if the line "Bike:"
   says the permission is missing.
3. Pair, once: switch the bike on, open the Bosch Flow app on your phone,
   **Add component → Accessories**, choose "Karoo BT Audio" (that is the Karoo).
   The bike asks nothing else; from then on it connects to the Karoo by itself
   whenever it is on.
4. Put the fields you want on your ride pages. For the sensor: Karoo
   **Sensors → add sensor**, choose BoschData → Bosch eBike.

## How it works

The bike is the one that scans and connects: the Karoo advertises as a Live
Data Interface accessory, the bike you paired finds it, connects, and the
extension subscribes to the bike's live data (speed, cadence, rider power,
battery, odometer, light and a few status flags, about once a second). The
link is read-only: nothing is ever written to the bike. Bluetooth stays on
only while BoschData is ON in the app.

The Karoo appears in the Flow app as an accessory named "Karoo BT Audio" (the
name of the Karoo's Bluetooth adapter, which an app cannot change) with the
Karoo's own battery level.

## Privacy

No network, no account, no data leaves the Karoo. A log of the link lives in
`/sdcard/Android/data/com.anpaiss.boschdata/files/bosch_data.log` on the device.

## Sibling extensions

- [boschRange](https://github.com/anpaiss/BetterETA/tree/v2-dashboard/boschrange): battery at the end of the route, from the route profile and your riding
- [BetterETA](https://github.com/anpaiss/BetterETA): arrival time that learns how you ride
- [PoiAhead](https://github.com/anpaiss/PoiAhead), [BigFields](https://github.com/anpaiss/BigFields), [AutoLoopKaroo](https://github.com/anpaiss/AutoLoopKaroo), [KarooAxsRatio](https://github.com/anpaiss/KarooAxsRatio)

## License

MIT, see [LICENSE](LICENSE). Not affiliated with Bosch or Hammerhead. "Bosch"
and "Smart System" are trademarks of Robert Bosch GmbH.
