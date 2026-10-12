# Open questions, blockers, risks

Last updated: 2026-10-11

## Waiting on Aditya
- Say "push" to deploy `/jetbrains` + `/payload-box` on space.ketoy.dev (website repo at `e4cfcff`).
- Predictor's deployed URL for the page and the email.
- Confirm the BosconSpace GitHub repo is public before the email goes out.
- Jumpers 19–21 and 11–13 are on; BMP280 still to wire for M3/M4.
- Power the Pi from the official 5 A supply; the Mac port browned it out
  with the Heltec attached.
- Order: E22-900M30S (flight mission radio), sensor batch 1 (SBM-20 board,
  AS7331, AS7341, BNO085, MS5611), USB UVC H.264 cameras ×2, USB 3 flash
  drive 64 GB for video, high-endurance A2 microSD ×2 for flight.
- Whether to apply the bootloader EEPROM update now (`sudo rpi-eeprom-update
  -a`); it may be what USB gadget mode needs.

## Unresolved
- GPS parked (Aditya, 2026-10-11): indoors; bridge pin sampling with
  pull-downs showed no Heltec pin driven, i.e. the NEO-6M is unpowered or
  unconnected. Future: GPS on the Pi's UART (pins 8/10).
- The Heltec's SX126x reports "SX1261" in its version string. Confirm the
  die (SX1261 = +15 dBm max, SX1262 = +22 dBm max) before trusting any
  22 dBm figure; see findings.
- USB gadget mode: Pi binds g_ether, Mac enumerates nothing. Try after the
  EEPROM update, with `dmesg -w` on the Pi while plugging in.
- Whether Koog-style tool calling works well enough with a 1.5 B model under
  our own grammar; to be measured at M9.

## Risks carried
- flightd's 24 h soak started 2026-10-11 19:30 IST; check `tools/pi_flightd.sh status` (NRestarts) and the /log files next session.
- Hardware watchdog not yet enabled (`RuntimeWatchdogSec=`); only the systemd service watchdog is.
- Kotlin/Native linuxArm64 is Tier 2: pinned to 2.4.10; bump only between
  test blocks.
- Hotspot address changes; always use `hab1.local`.
- Inference power ~10 W (others' measurement) vs docs/01 budget 5.4 W —
  re-measure and update docs/01 §7.
- Vision model memory on 4 GB — must be measured before promising the demo.
