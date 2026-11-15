# BoschData

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://gist.githubusercontent.com/anpaiss/78cf5eb76ad50594402b9778008a00a8/raw/BoschData-card-dark.svg">
  <img alt="downloads and latest release" src="https://gist.githubusercontent.com/anpaiss/78cf5eb76ad50594402b9778008a00a8/raw/BoschData-card-light.svg">
</picture>

Your Bosch Smart System e-bike on the Hammerhead Karoo, over Bluetooth, through
the Bosch **Live Data Interface**, the interface Bosch published in May 2026 for
accessories.

For a link that comes back by itself every time you switch the bike on, you
need a small extra board, a [BEStie](https://github.com/bestie-org/BEStie): the
board stays paired with the bike and the Karoo reads the board. Without it the
Karoo can pair with the bike directly, but the link lasts only until the bike or
the Karoo's Bluetooth is switched off (see [Why the board](#why-the-board)).

## What you get

**Seven data fields**, to put on any ride page:

| Field | Shows |
|---|---|
| Bike battery | The battery % exactly as the bike displays it ("charging" when plugged in) |
| Rider power (Bosch) | Your watts, from the Bosch torque sensor |
| Bike speed (Bosch) | Wheel speed, km/h |
| Cadence (Bosch) | Your cadence, rpm |
| Bike odometer | Total km of the bike |
| Bike light | Light ON/OFF and the ambient brightness the bike measures |
| BEStie battery | The board's own battery %, red below 20 %: time to charge it |

The bike fields show `--` while the bike is off, the board field while the board is not found.

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

- Karoo 3 (the Karoo 2 cannot act as a Bluetooth accessory).
- A Bosch Smart System bike with the Live Data Interface: control unit
  firmware v19 (May 2026) or later, drive units CX Gen4, CX Gen5, SX. Check
  with the Bosch Flow app that "Add component → Accessories" exists.
- For the automatic link: a BEStie board, an nRF52840 board (tested: Adafruit
  Feather nRF52840 Express with a 500 mAh LiPo) flashed with the Karoo build of
  the BEStie firmware: [my fork](https://github.com/anpaiss/BEStie), GPL-3, of
  [bestie-org/BEStie](https://github.com/bestie-org/BEStie), which adds a small
  BoschData service the Karoo reads. Firmware files on the
  [firmware release](https://github.com/anpaiss/BEStie/releases/latest).
  **Step-by-step guide**, from the parts to buy to the board on the bike:
  [Build the board](https://lagorai.bike/extensions/boschdata/board/) (copy in
  [docs/BOARD.md](docs/BOARD.md)).

## Install

1. Download `BoschData.apk` from the [latest release](https://github.com/anpaiss/BoschData/releases/latest)
   and sideload it from the Hammerhead companion app, or install it from the
   Karoo extensions library once it is listed there.
2. Open BoschData on the Karoo and press **GRANT BLUETOOTH** if the line "Bike:"
   says the permission is missing.
3. Pair, once.
   - **With the board** (box "Through the BEStie board" ticked, the default),
     built and flashed as in [the guide](https://lagorai.bike/extensions/boschdata/board/):
     power the board, switch the bike on, Bosch Flow app → **Add component →
     Accessories** → "BEStie". The Karoo finds the board by itself; from then on
     the bike reconnects to the board whenever it is switched on.
   - **Direct link** (box unticked): switch the bike on, tap **PAIR BIKE** on
     the Karoo, then in Flow **Add component → Accessories** → "Karoo BT Audio"
     (that is the Karoo). Repeat after every Bluetooth restart.
4. Put the fields you want on your ride pages. For the sensor: Karoo
   **Sensors → add sensor**, choose BoschData → Bosch eBike.

## Tested

- Garage, 10 November 2026: 752 messages from the bike, none lost; data on the
  Karoo 3 s after switching the bike on; bike off and on again, the board
  reconnected it alone in 2–4 s.
- First real ride, 15 November 2026: board linked for 42 minutes, 10,175
  messages from the bike, 0 dropped, no reconnection needed; bike battery
  100 → 90 % in the FIT file every 5 s; the bike odometer moved about 6 km,
  the same as Bosch Flow. Board battery 96 → 95 %.
- Not available: the assist mode. The current Bosch firmware does not send it
  over the Live Data Interface.

## How it works

The bike is the one that scans and connects: a Live Data Interface accessory
advertises, the bike it was paired with finds it, connects, and the accessory
subscribes to the bike's live data (speed, cadence, rider power, battery,
odometer, light and a few status flags, about once a second). The link is
read-only: nothing is ever written to the bike.

With the board, the board is that accessory and passes the data on to the
Karoo, together with its own status and log, through a small Bluetooth service.
Without it, the Karoo itself is the accessory; it appears in Flow as "Karoo BT
Audio" (the name of the Karoo's Bluetooth adapter, which an app cannot change).
Bluetooth stays on only while BoschData is ON in the app.

### Why the board

The bike reconnects only to the exact Bluetooth address it saw when it was
paired. Android gives the Karoo a new private address every time advertising
restarts and about every 15 minutes, and an app cannot ask for a fixed one. So a
direct link works for one session per pairing. The board has a fixed address
and is always where the bike expects it.

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

If you build on this code, or on its ideas, a mention is much appreciated: a link to this repository is enough.
