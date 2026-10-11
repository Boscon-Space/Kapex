# Open questions, blockers, risks

Last updated: 2026-10-11

## Waiting on Aditya
- Jumpers 19–21 and 11–13 are on; BMP280 still to wire for M3/M4.
- Power the Pi from the official 5 A supply; the Mac port browned it out
  with the Heltec attached.
- Order: E22-900M30S (flight mission radio), sensor batch 1 (SBM-20 board,
  AS7331, AS7341, BNO085, MS5611), USB UVC H.264 cameras ×2, USB 3 flash
  drive 64 GB for video, high-endurance A2 microSD ×2 for flight.
- Whether to apply the bootloader EEPROM update now (`sudo rpi-eeprom-update
  -a`); it may be what USB gadget mode needs.

## Unresolved
- GPS: the bridge forwards the NEO-6M's UART but sees 0 sentences; the
  module is not wired to the bridge Heltec (pins 4/5, 3V3, GND) since the
  board was moved. Reconnect and check the OLED's `gps` counter.
- The Heltec's SX126x reports "SX1261" in its version string. Confirm the
  die (SX1261 = +15 dBm max, SX1262 = +22 dBm max) before trusting any
  22 dBm figure; see findings.
- USB gadget mode: Pi binds g_ether, Mac enumerates nothing. Try after the
  EEPROM update, with `dmesg -w` on the Pi while plugging in.
- Whether Koog-style tool calling works well enough with a 1.5 B model under
  our own grammar; to be measured at M9.

## Risks carried
- Kotlin/Native linuxArm64 is Tier 2: pinned to 2.4.10; bump only between
  test blocks.
- Hotspot address changes; always use `hab1.local`.
- Inference power ~10 W (others' measurement) vs docs/01 budget 5.4 W —
  re-measure and update docs/01 §7.
- Vision model memory on 4 GB — must be measured before promising the demo.
