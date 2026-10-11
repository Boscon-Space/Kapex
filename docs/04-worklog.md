# Work log

Newest session first. One entry per session; bullets are what actually happened.

## 2026-10-11 — plan, docs, Pi bring-up, first Kotlin code

**Research and plan**
- Wrote `docs/35-pi5-kotlin-payload-computer.md`: Kotlin options on the Pi 5,
  JVM vs Native, radios, sensor candidates, memory/storage budgets, build plan.
- Aditya's direction changed the recommendation: one Kotlin/Native binary,
  custom HAL, llama.cpp linked in, beacon stays C++. Board corrected to 4 GB,
  microSD not NVMe. Two 720p cameras planned; video goes to a separate USB
  drive. Only two Heltecs in hand; E22-900M30S to be ordered.

**Pi bring-up** (full lesson list in `03-findings.md` and docs/35 §9)
- Downloaded and verified the Trixie Lite image; wrote `tools/image_card.sh`,
  imaged the EVO Plus card (the write and later raw-partition reads ran in
  Aditya's terminal because they need sudo).
- First attempt used `custom.toml` → ignored. Rewrote as cloud-init
  `user-data` with key-only SSH user `hab`, I²C/SPI/UART, USB gadget on.
- USB gadget: Pi side fine, Mac never saw the device. Charge-only cable was
  one real problem (fixed, verified with a Pixel 6a); still no enumeration
  with a data cable. Parked.
- Switched bench networking to the phone hotspot. First try didn't apply
  because the shipped `instance_id` key is ignored by cloud-init; fixed by
  writing `instance-id`. Then SSH wasn't enabled; fixed with `enable_ssh` +
  the `ssh` flag file. Logged in at 16:0x.
- `tools/pi_firstboot_setup.sh`: packages, partitions (sfdisk after parted
  refused), journald persistent, interfaces. Root 8 GiB, `/log` 4 GiB.

**Kotlin**
- `payload/` Gradle KMP project: `hal` (GPIO chardev v2 + I²C over ioctl,
  libc only) and `probes` (`gpio_probe`: chip label, loopback GPIO17→27 with a
  jumper, I²C scan + Bosch id). Gradle 9.8.1 + wrapper, Kotlin 2.4.10 pinned.
- First build failed only on the task name (`linkGpio_probe…`). Real build:
  one-time toolchain download (aarch64 gcc 8.3 / glibc 2.25 / kernel 4.9
  sysroot), then a 655 KB ELF.
- **Ran on the Pi (16:10):** `PASS chip pinctrl-rp1 54 lines`; `FAIL loopback
  10/20` with no jumper fitted (input reads constant low — the expected
  failure signature); `PASS i2c scan` with an empty bus. Exit code 1 as
  designed. Dynamic deps: libc, libm, libpthread, libdl, libresolv, libcrypt,
  librt, libutil, libgcc_s — all from the base image, nothing installed.
- Jumper fitted on pins 11–13 (drawn in `docs/pi5-header.html` because the
  Pi 5 header is unlabelled): `PASS loopback 20/20`. M2 done.
- `edge_probe` (coroutines; blocking `read()` on the line fd in Dispatchers.IO,
  toggling from another coroutine): 2000 pulses at 500 µs and 100 µs half
  periods, all 4000 edges seen, seqno contiguous. Effective toggle rate at the
  100 µs setting was ~2.95 kHz (2000 pulses in 678 ms) — `usleep` overhead,
  not a kernel limit. Binary 1.19 MB with kotlinx-coroutines. M5 done.
- Wrote `drivers/` module: BMP280 driver (datasheet integer compensation,
  unit test against the Bosch example values) and `baro_probe`. **Unit tests
  pass on the Mac (2/2):** the integer compensation reproduces the Bosch
  example (25.08 °C, 1006.53 hPa) and calibration parsing is checked. Sensor
  not yet wired.
- Added `LinuxSpiBus` (spidev, SPI_IOC_MESSAGE) to the HAL and `spi_probe`
  (MOSI→MISO loopback, 1280 bytes at 1/4/8 MHz, needs a jumper on pins 19–21).
- `spi_probe` on the Pi: PASS at 1, 4, 8 MHz, 1280/1280 bytes — but the
  jumper was already fitted, so the failing case had not been seen. Jumper
  pulled: FAIL 1279/1280 at all three speeds, MISO reads constant 0x00 (the
  one match is the ramp's own zero byte). Refitted: PASS 1280/1280 at
  1/4/8 MHz. M6a done. HAL now verified on the board for GPIO in/out, edge
  events, SPI; I²C transaction path exercised (bus empty).
- Radio path started: `LinuxSerialPort` (termios + poll) in the HAL;
  `firmware/23_spi_bridge` (Heltec as a dumb SPI/GPIO adapter over USB-CDC at
  921600, compiled 283 KB, **not uploaded** — needs a decision on which Heltec
  to repurpose); `Sx1262Bus` interface, `BridgeBus`, minimal `Sx1262` driver
  (reset, GetStatus, Read/WriteRegister, version string at 0x0320), and
  `radio_probe`. Built (611 KB) after one termios type fix; staged on the Pi.
  Without the bridge plugged in it fails on open(/dev/ttyUSB0) — the failing
  half of M6.
- Aditya chose the **TX Heltec** (was `08_telemetry_tx`) for the bridge.
  Flashed `23_spi_bridge` from the Mac (compile, then upload, port
  `/dev/cu.usbserial-0001`). Ping over the Mac's serial port: `pHAB1` on the
  first attempt. Lesson: opening the port resets the ESP32 via DTR/RTS; the
  Kotlin `BridgeBus.ping` now drains and retries for up to 4 s.
- Heltec moved to the Pi's USB-A: the Mac-powered Pi **browned out and
  rebooted** (Undervoltage detected, throttled=0x50000). Came back on the
  hotspot; Heltec enumerated as `/dev/ttyUSB0`.
- First `radio_probe` run failed on the ping: the cp210x delivers the reply
  in pieces and the check read only the first. Fixed (accumulate). Then
  **ALL PASS**: ping, reset → BUSY low within 100 ms, GetStatus 0x2A
  (STDBY_RC), version register 0x0320 = "SX1261 V2D 2D02". M6 done: the
  radio is driven from Kotlin on the Pi with no radio logic in C++.
- Pi moved to the official 5 A supply, Heltec on USB-A: throttled=0x0,
  5.14 V at the PMIC input, `radio_probe` ALL PASS. Bench is now in its
  steady configuration: Pi on PSU, Heltec bridge on USB, Mac over Wi-Fi.
- BMP280 wired to pins 1/3/5/9 (wiring figure added to `docs/pi5-header.html`).
  First attempt: bus clean (SDA/SCL idle high) but empty — a wiring error,
  fixed by Aditya. Then `i2cdetect` shows 0x76; `gpio_probe` reads id 0x58;
  `baro_probe` ALL PASS: 937.71 hPa mean, 31.47 °C, spread 0.017 hPa over
  10 readings in 2 s. M3 done. (Pune ≈ 560 m → ~947 hPa standard; 937.7 is
  plausible for the day; absolute accuracy is M4's job.)
- The 11–13 loopback jumper lost contact during rewiring (10/20 again);
  reseated, and the 19–21 one too (same MISO-stuck-at-0 signature). Final
  run, all five probes with their own arguments: **gpio, edge, spi, baro,
  radio ALL PASS**. (One false alarm on the way: I passed the edge probe's
  arguments to every probe; `tools/run_all_probes.sh` now runs the set
  correctly.) `i2cdetect` lives in /usr/sbin, not on the ssh PATH.

**End of session state:** bench = Pi 5 on the 5 A supply, Heltec TX as the
SPI bridge on USB, BMP280 on I²C, jumpers 11–13 and 19–21, Mac over the
phone hotspot. Milestones M0–M3, M5, M6a, M6 passed. Next: M7 (first prompt
round trip on 867.3 MHz GFSK) needs the RX Heltec's role decided; parts
orders listed in `06-open-questions.md`.
  `baro_probe` without a sensor: FAIL, errno 121 (the failing half of M3).

**Housekeeping**
- Memory note `kotlin-everywhere-jetbrains` kept current.
- This `payload/docs/` folder created as the durable session record.
