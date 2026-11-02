# BEStie board: first power-up and test, step by step

Everything below is prepared; nothing has run on real hardware yet. Files on
the PC:

| What | Where |
|---|---|
| Bootloader for the Feather Express (SoftDevice 7.3.0, mandatory first) | `C:\Users\Andrea\AndroidStudioProjects\BEStie-flash\official\feather_nrf52840_express_bootloader-v0.11.0_s140_7.3.0.hex` |
| Stock BEStie firmware v0.2.2 (fallback, no Karoo service) | `C:\Users\Andrea\AndroidStudioProjects\BEStie-flash\official\bestie_nrf52840_feather.hex` |
| **Our firmware** (BEStie + BoschData Service for the Karoo) | `C:\Users\Andrea\AndroidStudioProjects\BEStie-flash\karoo\bestie_nrf52840_feather_karoo.hex` |
| Firmware sources, branch `feature/karoo-service` | `C:\Users\Andrea\AndroidStudioProjects\BEStie` |
| BoschData debug APK (board mode is the default) | `C:\Users\Andrea\AndroidStudioProjects\BoschData\app\build\outputs\apk\debug\app-debug.apk` |

Rebuild the firmware (PowerShell, inside the BEStie folder):

```
docker run --rm -v "${PWD}:/workdir" -w /workdir bestie-dev-container sh -c "make -j BUILD=release BOARD=nrf52840-feather"
```

## 1. Battery and bootloader

1. Check the LiPo plug polarity against the `+`/`-` marks next to the JST
   connector of the Feather before plugging it in (Amazon packs come both
   ways). Plug the battery, then the USB cable.
2. Enter the bootloader: hold both buttons, release RESET, keep holding the
   other one until a USB drive appears (`CURRENT.UF2`, `INFO_UF2.TXT`).
3. Open `INFO_UF2.TXT`. If it does not say `SoftDevice: S140 7.3.0`, flash the
   bootloader first: https://bestie-org.github.io/adafruit-uploader/ in
   Chrome, "Connect Serial", "Single .hex (auto detect)", pick the bootloader
   hex, Upload, wait 30 s more, unplug and plug again, re-enter the bootloader
   and check `INFO_UF2.TXT` again.

## 2. Our firmware

1. Bootloader mode again, uploader page, pick
   `bestie_nrf52840_feather_karoo.hex`, Upload.
2. Unplug and plug the USB: no drive must appear (a drive means the app does
   not boot: flash the stock hex to tell a board problem from a firmware one).
3. On the phone, nRF Connect: a scan shows `BEStie`. Connect: the services
   are Battery (180F), Fitness Machine (1826) and one 128-bit
   `00000001-6b61-726f-6f00-616e70616973` with three characteristics. That
   is the BoschData Service: LIVE (…0002), STATUS (…0003), LOG (…0004).
   Enabling notifications on LOG in nRF Connect replays the boot line, e.g.
   `3s boot #1 reason 0x0 fw 0.2.2-k1 bonds 0`.

## 3. Bike pairing (Flow)

1. Bike on, board powered. Flow app → Add component → Accessories → `BEStie`.
2. The board's LOG (nRF Connect, or later the Karoo log) must show, in order:
   `link up h0 …`, `sec start …`, `sec ok … bonds1`, `bike up: soc NN% …`.
   `sec FAILED` means the bond did not complete: remove the component in
   Flow and pair again.
3. Switch the bike off and on: the board must log `link down` and then
   `link up` + `bonded peer` + `bike up` by itself, without Flow. That is
   the whole point of the board.

## 4. The Karoo

Bell OFF first (control center, System card).

```
adb install -r C:\Users\Andrea\AndroidStudioProjects\BoschData\app\build\outputs\apk\debug\app-debug.apk
adb shell pm grant com.anpaiss.boschdata android.permission.BLUETOOTH_SCAN
adb shell pm grant com.anpaiss.boschdata android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.anpaiss.boschdata android.permission.BLUETOOTH_ADVERTISE
adb shell am start -n com.anpaiss.boschdata/.MainActivity
```

In the app: "Through the BEStie board" ticked, "BoschData ON" ticked. The
"Bike:" line goes: `looking for the board 'BEStie' (fast)` → `board found,
connecting…` → `board linked, bike UP, board batt NN %, up N min, boot #N`.

Read the log while it happens:

```
adb shell tail -f /sdcard/Android/data/com.anpaiss.boschdata/files/bosch_data.log
```

Expected sequence, tags in brackets:

```
[BEST ] Ricerca della scheda n. 1 (veloce, nome 'BEStie')
[BEST ] Scheda trovata: XX:XX:XX:XX:XX:XX 'BEStie' rssi -60 dBm, ...
[BEST ] Connessione n. 1 a XX:...
[BEST ] Scheda XX:...: CONNESSO, ok
[BEST ] MTU 247 (status 0)
[BEST ] Servizi di XX:... : 1801, 1800, 180f, 1826, BDS:0001
[BEST ] Scheda XX:... salvata
[STAT ] fw 0.2.2-k1, up 3 min, boot #1 (power on), bike UP, 180 LDI msgs, last 1 s ago, links 2, bonds 1, batt 97 %, dropped 0
[BEST ] LIVE (lettura, 40 byte): speed=0.00 km/h cadence=0 ... SOC=44 % ...
[BEST ] Notifiche LOG abilitate
[BRD  ] 3s boot #1 reason 0x0 fw 0.2.2-k1 bonds 1        <- the board's own log, replayed
[BRD  ] 41s link up h0 ...
[BRD  ] 42s sec ok h0 proc1 bonds1
[BRD  ] 43s bike up: soc 44% odo 1020714 m light 2
[BRD  ] 190s karoo h1 reads the log
[BEST ] Notifiche STATUS abilitate
[BEST ] Notifiche LIVE abilitate
[BEST ] Scheda collegata: fw 0.2.2-k1, ...
[BEST ] Bici UP secondo la scheda (LIVE in arrivo)
[BIKE ] Bici collegata: dati in arrivo
[BIKE ] Batteria 44 %, odometro 1020.7 km
```

Then the six fields on a ride page turn green; `[STAT ]` lines come only when
something changes (bike up/down, links, bonds, boots, a fault); a heartbeat
every 5 min; `[BRD ]` lines as the board logs them.

## 5. What to try, and what the log must say

| Try | Expected |
|---|---|
| Bike off | board: `bike down after N msgs`; Karoo: `[STAT ] ... bike down`, `Bici DOWN secondo la scheda (STATUS)`, fields red with "last" values |
| Bike on again | board reconnects it alone (`link up`, `bonded peer`, `sec ok`, `bike up`); Karoo: `Bici UP` within a few seconds, fields green |
| Walk away with the Karoo | `Scheda XX: disconnesso, timeout di supervisione`, `Ricerca della scheda n. 2`; back in range: found and linked again, LOG ring replayed |
| Karoo reboot | BoschData restarts, finds the board (address saved), links; the board's STATUS still says `boot #1`: the board never noticed |
| Board power cycle | Karoo: `Scheda muta da 60 s` or supervision timeout, then rescan; board: `boot #1 reason 0x0` (RAM lost with power: boots restart from 1) |

## 6. If something is wrong

- **Board not found by the Karoo**: nRF Connect on the phone sees `BEStie`?
  No → firmware or power (section 2). Yes → `adb logcat -s BluetoothAdapter
  BtGatt.ScanManager` while the app scans; the Karoo's scan is filtered on the
  exact name `BEStie`.
- **Found but `NON ha il BoschData Service`**: the stock hex is on the board,
  flash ours.
- **`sec FAILED` on the board / Flow does not complete**: remove the
  accessory in Flow, power-cycle the board, pair again. The board keeps bonds
  across power loss (flash); `FORGET BOARD` on the Karoo does not touch them.
- **`boot #N` growing, `LAST FAULT ...` in STATUS**: the firmware crashed and
  rebooted; the fault line says file:line (SDK error) or pc/lr (hard fault).
  `arm-none-eabi-addr2line -e build/bestie_nrf52840_feather.out 0x<pc>`
  inside the Docker container gives the source line of a hard fault.
- **LIVE lines come, fields stay `--`**: the LiveData decoder; the `[BEST ]
  LIVE (...)` line shows what the board sent.
- **`dropped N` growing**: notifications dropped on the board (queue full):
  the Karoo link is slow; the counters tell how many.
- **Nothing in the Karoo log at all**: `adb shell dumpsys activity services
  com.anpaiss.boschdata` shows whether the extension service runs; the Karoo
  starts it when the extension is enabled in its Extensions menu.

## 7. Keep after the test

```
adb pull /sdcard/Android/data/com.anpaiss.boschdata/files/bosch_data.log C:\Users\Andrea\AndroidStudioProjects\BoschData\logs\bosch_data_bestie_day1.log
```
