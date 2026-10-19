# Work log

Newest session first. One entry per session; bullets are what actually happened.

## 2026-10-19: Sun Devil Rocketry review; baro altitude fixed above 11 km

- Read 18 of Sun Devil Rocketry's (ASU) avionics repos for practices to adopt.
  Report: `../../docs/36-sun-devil-rocketry-review.md`.
- Their two-layer ISA function jumps 3 887 m at 11 km. Checking ours for the
  same class of error found that `baroAltitudeM` (protocol and analysis) and
  the transmitter sketches were single-layer: 4 719 m low at 30 km geometric
  (computed against US Std Atm 1976).
- Tests at 20 km (5529.3 Pa) and 30 km (1197.0 Pa) added first and seen to
  FAIL: 8 failures (4 per target, JVM and macOS native).
- Replaced with the three-layer standard atmosphere returning geometric
  altitude. Kotlin 34/34 pass; `station`, `flightd`, `probes` compile.
  `analysis.baroAltitudeM` lost its unused `seaLevelHpa` parameter.
- Not deployed: the Pi still runs the old `flightd`. `linuxArm64` tests not run
  (cannot run on the Mac).

## 2026-10-12 (evening): Station installed as a Mac app

- Aditya: install the station so it need not be run from Gradle. Packaged
  with Compose's `createDistributable` (jpackage, bundles its own runtime):
  170 MB `HAB-1 Station.app` in `/Applications`, launched and polling the Pi.
  Two packaging facts: Compose refuses to package with a Homebrew JDK, so
  `STATION_JDK` points at the Corretto 22 on this Mac (`station/build.gradle.kts`
  reads it); macOS rejects an app version with a leading zero, so the app is
  `1.0.0`. `tools/install_station.sh` repeats the whole thing.
- The app finds the repo through `~/.hab1-station.properties` (`repo=`),
  written now; without it an app launched from /Applications would not find
  the tools.
- Removed the 18 em dashes from the station's UI strings (rule in CLAUDE.md).
- Crash on the Analysis tab in the installed app: `UnsupportedClassVersionError`,
  `analysis.Rolling` compiled as class file 70 (Java 26, Gradle's own JVM)
  while the bundle ships a Java 22 runtime. The library modules had no
  toolchain pin; `protocol`, `analysis`, `agent` now `jvmToolchain(21)` like
  the station (verified: jar class version 65). Reinstalled.
- Not done: a login item (start at login) awaits Aditya's yes.

## 2026-10-12 — JetBrains pitch page and email

- Aditya's facts for the pitch (his statements, not measured by us): first
  launch **24 October 2026**, reserve **31 October**; clearances from the
  Government of India, the Ministry of Civil Aviation and the Indian Air
  Force are in hand; November/December flights add inter-communication
  modules and multiple ground stations. Tone: company vision, not a project.
- Built `/jetbrains` inside the live site's repo (`website/`, separate git
  repo `developerchunk/BosconSpace-web`, Vercel, cleanUrls): same nav, hero,
  thesis, story, ledger, timeline, contact and motion as the home page; a
  facts grid of the 11 Oct bench numbers; two asks (Kotlin/Native contact,
  tell the story together); links to the repo, the Kotlin write-up and the
  3D payload box hosted at `/payload-box`. Verified in the browser at desktop
  and phone widths. Committed as `e4cfcff` in the website repo, **not
  pushed** (push = production deploy; waiting for Aditya's "push").
- Earlier standalone page kept at `jetbrains-page/` in this repo (GitHub
  Pages variant) in case it is wanted; superseded by the site route.
- Email draft given in chat; placeholders: predictor URL (not found anywhere
  in the repos; `boscon-hab-predictor/` is the source, deployed URL unknown)
  and recipient name. Open: is `developerchunk/BosconSpace` public?
- Website follow-ups the same day: the Kotlin write-up became its own page `/kotlin`
  (generated from `07-kotlin-on-the-balloon.md` by `website/tools/build_kotlin_page.py`),
  `/payload-box` and `/jetbrains` became directories with `index.html`, new
  photography, the predictor linked (https://boscon-hab-predictor.vercel.app/).
  Pushed and live at `ebe27de`; all three URLs returned 200 on space.ketoy.dev.
  `developerchunk/BosconSpace` is still PRIVATE, so no repository link is on the site.
- **Renamed the project to Kapex** (Aditya's choice; see 02-decisions 2026-10-12):
  folder `payload/` to `kapex/`, Gradle root `kapex`, packages `space.boscon.hab.*` to
  `space.boscon.kapex.*` (14 source directories moved, 58 files edited), station code
  that located `payload/tools`, the llama cinterop paths, `tools/check_packet_abi.py`,
  CLAUDE.md. Verified on the Mac: 28 unit tests, 0 failures, from the new folder; the
  Linux flight binary, the gpio probe and the station compile and link; `flightd.kexe`
  is 1,969,120 bytes with 281 symbols naming kapex and none naming the old package;
  `check_packet_abi.py` agrees on 11 fields, 28 bytes. A deliberately wrong entry point
  (`space.boscon.hab.flightd.main`) failed the build with "Could not find
  'space/boscon/hab/flightd/main' function", then was restored. **Not verified:** the
  renamed binary was not deployed to or run on the Pi, and the station was compiled but
  not launched. Staged in git as 83 renames, not committed. Old worklog entries keep the
  old names on purpose.
- **Kapex moved to its own repository**, https://github.com/developerchunk/Kapex
  (PUBLIC, was empty). The 16 earlier commits of `payload/` were extracted with
  `filter-branch --subdirectory-filter` from a clean clone and kept, then one commit
  records the rename (41 package moves detected as renames). Before the push the whole
  history was scanned for secrets: no password, key, token or hash; the only personal
  data is the Pi login `hab@hab1.local`, two private LAN addresses (10.12.194.1,
  10.18.98.33) and a local home path in `llm/.../llama.def`; no file over 200 KB was ever
  committed. The scan was pattern based, not exhaustive. Commit author identity
  `Developer Chunk` and its email are visible in the public history.
- The parent repo now holds `kapex/` as a git submodule pinned to the pushed commit.
  Proven with a fresh `git clone --recurse-submodules`: 84 files, 17 commits, no old
  `payload/` folder. Work on Kapex is committed and pushed inside `kapex/`, then the
  parent's pointer is bumped with a commit in the parent.
- New strict rule in CLAUDE.md: no em dashes anywhere. Site pages and this folder's
  README and Kotlin write-up are clean; older docs here still have some, to be removed
  when touched. Checker: `website/tools/check_no_em_dash.py`.
- Known rough edges for a public repo: `README.md` and `docs/00-README.md` link to
  parent-repo paths (`../docs/35-...`, `../../CLAUDE.md`) that do not exist in the
  standalone repo; `llm/.../llama.def` has an absolute path under `/Users/adityashinde`.
- 2026-10-13: Apache License 2.0 added as `LICENSE` (Aditya's choice), text taken from
  apache.org (11,323 bytes) and identical to GitHub's copy apart from blank lines at the ends; README has a License
  section. No copyright holder line or NOTICE file was added, because who holds the
  copyright (Aditya or Boscon Space) has not been decided. Open: that decision, and
  whether to add the optional per-file header.

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

**Telemetry transmitter in Kotlin (same session, after the commit)**
- Committed the day's work as `2c918df`.
- Read firmware/08 + 09 and the installed RadioLib 7.7.1 sources to reproduce
  the GFSK link register-for-register: 866.1 MHz, 38.4 kb/s, ±40 kHz,
  RX BW 156.2 kHz, preamble 16 bits, sync 2D D4, CRC-16 (init 0x1D0F, poly
  0x1021, inverted), **no Gaussian shaping and whitening off** (7.7.1's
  beginFSK defaults), TCXO 1.6 V on DIO3, DC-DC regulator, DIO2 as RF switch,
  PA table row for 22 dBm = (duty 4, hpMax 7, paVal 22), the GFSK register
  fixes (0x06D1, 0x089B, 0x08B8, 0x06AC), PA clamping fix (0x08D8 |= 0x1E).
- New `protocol/` module: `TelemetryFrame` (28 bytes, little-endian) with
  `FIELDS` that `tools/check_packet_abi.py` now compares against the C struct
  (passes); `NmeaParser` (GGA/RMC, any talker); `Sx1262.beginGfsk/transmit`;
  `BridgeBus.drainGps`; `firmware/23_spi_bridge` v2 forwards the NEO-6M's
  bytes on 'N' (compiled 284 KB, **not yet uploaded**); `telemetry_tx`
  program printing lines in 09's format. All 14 unit tests pass on the Mac
  (frame layout/offsets/round-trip/altitude, NMEA, bit-rate 26666, fdev
  41943, frf 908171648 = RadioLib's float32 value, PA table).
- Bridge v2 flashed (ping OK). Two driver bugs found on the board, both
  about the SX126x status byte: (1) the wake-up NOP's status carries the
  previous command's flags — RadioLib does not check it; (2) **until
  SetDIO3AsTCXOCtrl is sent, every command reports "failure to execute"
  (0xAA)** because the Heltec's TCXO has not started — RadioLib ignores
  status up to that point. Fixed by mirroring that.
- **Kotlin transmits.** 15 then 60 frames, each confirmed by TX_DONE,
  ~37 ms per frame including bridge round trips, status 0x22 after config,
  BMP280 data in every frame (937.9 hPa, 30.8 °C). GPS: 0 sentences — the
  NEO-6M is probably no longer connected to the Heltec after it was moved.
  **Aditya confirmed the RX Heltec (09_telemetry_rx, TFT) shows the Pi's
  frames.** First telemetry on air from Kotlin. Bridge v3 adds the OLED
  status page (seq, hPa, °C, tx-done count, GPS sentences) and the white-LED
  heartbeat on TX_DONE; flashed, ping OK.
- Aditya: the beacon must run continuously. `systemd/hab1-telemetry.service`
  (Restart=always, 5 s) + `tools/pi_service.sh install|status|restart|log`
  installed and enabled on the Pi: `telemetry_tx 0 22 /dev/ttyUSB0` forever,
  starts at boot, restarts if the bridge drops. **Running:** picked the
  Heltec up by itself, `TX ok in 63 ms` per frame (the OLED redraw adds
  ~25 ms to the bridge round trips), BMP280 in every frame. GPS still 0
  sentences: the NEO-6M is not connected to the bridge Heltec.

**Flashing the bridge from the Pi (no more trips to the Mac)**
- Aditya asked whether the TX firmware can be updated from the Pi itself and
  to make sure there is a way out of a bad flash. `tools/flash_bridge.sh`
  (Mac: arduino-cli compile with `--output-dir`, push bootloader /
  partitions / boot_app0 / app to `~/bridge/new`) + `tools/pi_bridge_flash.sh`
  (Pi: stop the beacon, `esptool --no-stub` over /dev/ttyUSB0 at the
  arduino-cli offsets 0x0/0x8000/0xe000/0x10000, ping; on no answer flash
  `~/bridge/last-good` back; restart the beacon).
- Debian's `esptool` 4.7.0 package lacks the ESP32-S3 stub flasher file, so
  it crashes unless run with `--no-stub` (ROM loader, 460800 baud, ~1 min).
- Verified: identical image flashed from the Pi, bridge answered, promoted to
  last-good, beacon resumed. **Rollback proven**: a zero-filled app image →
  no answer → last-good flashed back → answering → beacon active.

**GPS diagnosis (parked)**
- Bridge v4 adds `'S' pin`: sample a GPIO 2000× with an internal pull-down.
  Flashed from the Pi twice via the new pipeline. Result: every header pin
  reads 0/2000 (floating); only GPIO0 (boot strap) is high. A powered UART
  TX idles high, so the NEO-6M is unpowered or not landing on any Heltec
  pin. Aditya parked it: indoors anyway, and the GPS will go on the Pi's own
  UART later.

**M8 started: llama.cpp in-process**
- Aditya OK'd ~1.2 GB over the hotspot. `tools/pi_llama_setup.sh` runs
  detached on the Pi: shallow clone of ggml-org/llama.cpp (commit a9d27ac,
  2026-10-11), native Release build (GGML_NATIVE, shared libs), install to
  `~/llama/install`, download Qwen2.5-1.5B-Instruct Q4_K_M (~1.1 GB) to
  `~/models`, then a C++ `llama-bench` baseline (4 threads, 128 tokens).
- This llama.cpp has `llama_model_params.load_mode` (enum: MMAP, MLOCK,
  MMAP_MLOCK …) instead of the older use_mmap/use_mlock booleans.
- New `llm/` module: cinterop on `llama.h` (headers + .so pulled from the
  Pi by `tools/pi_llama_pull.sh`, rpath `/home/hab/llama/install/lib`),
  `Llm` wrapper (load mlocked, ChatML prompt, greedy sampling, max-token and
  wall-clock guards), `llm_probe` (two fixed prompts, ≥ 8 tok/s, 17×23=391).
  Build/link fixes: include path via the `.def`, `llama.h`/`libllama.so`
  missing from llama.cpp's install tree (pulled from src/build), link flags
  belong in the `.def` so the probes executable inherits them, lld wants
  `--rpath=`. Then **`llm_probe` ALL PASS on the Pi**: 11.9 tok/s generation
  vs 12.06 C++ baseline, deterministic answers, eos stops, 17×23=391. M8
  speed done; power still unmeasured (no inline meter).

**M9: agent loop**
- New `agent/` module (common Kotlin): `LlmEngine` interface, `Tool`,
  `Guards` (ERR_BUSY / ERR_HOT > 80 °C / ERR_LOWBAT < 9.6 V, checked before
  the model is touched), `AgentLoop` (ChatML turns; the model calls a tool by
  replying `CALL <name>`; result fed back as a user turn; tool budget;
  unknown-tool handling). Six scripted-engine tests on the Mac (tests="6" skipped="0" failures="0").
- `agent_probe` on the Pi with the real model and the real BMP280 behind
  `read_sensors`: **ALL PASS** — "What is the air pressure around you?" →
  CALL read_sensors → "The air pressure around me is 938.5 hPa." (live
  reading 938.5 hPa; 5.9 s, two model rounds); sky question answered
  directly (3.6 s, no tool); guard tripped at 86 °C → ERR_HOT in 0 ms.
  Qwen2.5-1.5B follows the one-line CALL grammar from a single system-prompt
  instruction, greedy sampling.
- Fan: Aditya confirmed the cooler is on the 4-pin socket; the device-tree
  node was `disabled` by the firmware. Forced on with `dtparam=cooling_fan=on`
  plus a 45/55/63/70 °C curve, rebooted: pwm-fan present, 3128 rpm, 48.8 °C,
  beacon back by itself. Setup script carries the same settings.

**M11: analysis module**
- `analysis/` (common Kotlin, no I/O): `Rolling` window stats,
  `AltitudeProfile` (250 m bins, ascent/descent apart) + `findPeak`
  (Regener-Pfotzer: max bin, ≥ 5 samples, ≥ 3× ground, not the top bin),
  `BurstDetector` (spike ≥ 3 g then vertical rate < −1 m/s within 20 s;
  free-fall ≤ 0.3 g for 0.5 s), `ChannelMonitor` (STUCK/RATE/STALE),
  `Reading.toPromptText()` ("unavailable", flags in brackets),
  `baroAltitudeM`. 8/8 tests on the Mac incl. a synthetic 0–30 km ascent
  with a Gaussian 14 cps peak at 19 km.

**M12: flightd**
- `flightd/`: one process — BMP280 → analysis (ChannelMonitor, Rolling
  vertical rate, AltitudeProfile) → 28-byte frame → beacon through the
  bridge → NDJSON `FlightLog` on /log (O_SYNC, 10-min rotation) → `Systemd`
  watchdog pet (sd_notify over a Unix datagram, no libsystemd; `sockaddr_un`
  declared in our own binding). Prompts: `PromptQueue` (a .txt dropped in
  ~/prompts; answer in /log/answers) → `AgentLoop` on Dispatchers.Default so
  the beacon never waits; guards fed with the SoC temperature each tick.
  `systemd/hab1-flightd.service` (Type=notify, WatchdogSec=30), installed by
  `tools/pi_flightd.sh install|status|log|prompt "…"`; replaces the
  beacon-only service.
- Two bench bugs: (1) I set `guards.busy` before handing the prompt to the
  agent, so it refused its own prompt (ERR_BUSY); (2) `scp` over the running
  binary fails with "text file busy" and the old binary kept running — the
  install now copies beside and renames, and prints both hashes.
- **Verified:** prompt → read_sensors → "The air pressure is 938.6 hPa, the
  temperature is 30.7 degrees Celsius, and the barometric altitude is 641
  meters…" while the beacon kept 1000 ± 6 ms cadence and zero TX failures;
  SIGKILL → restart (counter 1); SIGSTOP → watchdog kill after 30 s, back in
  40 s. The model's "this suggests a high-altitude environment" at 641 m is
  the usual 1.5 B-model flourish; the numbers it quoted were the live ones.
- Left running for the 24 h soak. Not yet: the hardware watchdog
  (`RuntimeWatchdogSec` in /etc/systemd/system.conf) and pack-voltage input
  to the guards (no battery on the bench).

**HAB-1 Station (Mac console)**
- Aditya asked for one Mac application showing and controlling everything:
  Pi connection, pins, Heltec/radio, data charts, prompts in/out, the Kotlin
  analysis, fan control, a code editor for the bridge sketch and the Kotlin
  modules with check/build/flash/deploy, Wi-Fi settings, hardware watchdog —
  all from real data, nothing sampled.
- `station/`: Compose for Desktop (CMP 1.12.1, Kotlin 2.4.10), JVM, reuses
  `protocol` and `analysis` as libraries. One SSH round trip every 2 s runs
  `POLL_SCRIPT` on the Pi (system, temp/throttle/PMIC volts, memory/disk,
  pwm-fan, services with NRestarts/StatusText, ttyUSB0, `pinctrl get 0-27`,
  tail of the newest /log NDJSON, flightd journal lines, nmcli, watchdog
  config); every panel renders from that snapshot or says "No connection".
  Tabs: Overview, Pins (live header drawing + kernel line table), Radio
  (cadence from log timestamps, flash-via-Pi), Data (four single-axis hover
  charts), Prompts (writes ~/prompts/*.txt over ssh, history from the log),
  Analysis (same Rolling/ChannelMonitor/AltitudeProfile code run on the Mac),
  Fan (rpm/pwm/curve, manual pwm override + auto), Code (editor with Save,
  Check = arduino-cli / gradle+tests, Flash via Pi, Build+deploy flightd),
  Settings (host/key/repo, Pi Wi-Fi via nmcli — password sent to the Pi,
  never stored —, hardware watchdog enable/disable via RuntimeWatchdogSec,
  reboot/poweroff). Charts follow the dataviz rules: one measure per axis,
  2 px line, validated palette hue, hover crosshair. Compiles clean; runs
  with `gradle :station:run`; verified alive from the Pi's side (sshd logs
  the station's key logins at the poll cadence). Screen capture from this
  session was refused by macOS (no Screen Recording permission), so the
  panels' appearance is Aditya's to judge; no panel shows sample data.

**Incident 19:57: bridge erased by a flash that raced flightd**
- Aditya reported the station's prompts not working. Cause chain: a bridge
  flash was started from the station at 19:57:42 while `hab1-flightd` owned
  /dev/ttyUSB0. `pi_bridge_flash.sh` only stopped the *old* `hab1-telemetry`
  service, so esptool wrote the chip while flightd kept talking on the same
  port: the app partition ended up erased (`invalid header: 0xffffffff` on
  the boot log at 115200), the rollback raced the same way, and the script
  then *started* hab1-telemetry, whose `Conflicts=` stopped flightd. Every
  flightd restart since hung at the bridge handshake until the 120 s start
  timeout. The station's Send had worked all along (files were in ~/prompts).
- Fix: the flash script now stops both services, kills any port user,
  refuses to flash if the port is still busy, and restarts the service that
  was running (flightd by default). Bridge reflashed from the Pi; flightd
  back. Lesson for the record: **one owner of /dev/ttyUSB0 at a time**, and a
  tool that takes the port must take it from everyone.

**Bridge unplugged for a check-up (Aditya) → flightd tolerance**
- With the Heltec removed: flightd stayed active, logged `tx error: write
  failed: Input/output error (errno 5)` each second, and still answered a
  prompt in 8 s with the live 939.3 hPa. But a *restart* without the bridge
  used to be FATAL. Changed: the radio is optional at any moment — attach at
  start if the port answers, otherwise retry every 10 s; detach on
  EIO/ENOENT/timeout and re-attach when it returns; `radio` flag in each
  tlm record. Station relaunch had failed on a `remember { isSystemInDarkTheme() }`
  compile error (composable inside remember); fixed. Heltec returned at
  20:30: flightd re-attached the radio by itself, 0 TX failures since.
- **Aditya confirmed the station: colours stable, prompts working in the
  app.** Overview and Pins were already showing live data.

**End of session state:** bench = Pi 5 on the 5 A supply with the fan on,
Heltec TX as the SPI bridge (v4) on USB, BMP280 on I²C, jumpers 11–13 and
19–21, Mac over the phone hotspot, **`hab1-flightd.service` running**:
beacon + sensors + analysis + LLM agent in one Kotlin binary, received by
the RX Heltec. Milestones M0–M3, M5, M6a, M6, M7a, M8, M9, M11 passed; M12
pending its soak. Next: M7 (prompt uplink over the radio into the same
queue), M4/M10 when sensors arrive, M13 cameras, power measurement with an
inline meter.

round trip on 867.3 MHz GFSK) needs the RX Heltec's role decided; parts
orders listed in `06-open-questions.md`.
  `baro_probe` without a sensor: FAIL, errno 121 (the failing half of M3).

**Housekeeping**
- Memory note `kotlin-everywhere-jetbrains` kept current.
- This `payload/docs/` folder created as the durable session record.
- 2026-10-13: removed every AI co-author line (`Co-Authored-By: Claude ...`) from the commit
  messages of this repository, the parent and the website. 20 of 23 Kapex commits had one.
  History was rewritten with git-filter-repo, so every Kapex commit hash changed (head
  `9f51ebb` became `1fcfb81`). Checked per commit: file contents, authors, dates and subjects
  are identical, only the message bodies differ. Old hashes quoted in older notes are stale.
  Rule and a local `commit-msg` hook now stop new ones; see CLAUDE.md in the parent repo.
