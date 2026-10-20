# Kotlin on the balloon

**HAB-1: a Raspberry Pi 5 running a local LLM under a hydrogen balloon, with
the whole payload computer written in Kotlin.**

The project is called **Kapex**: Kotlin plus apex, the top of the flight. Maintained
document. Last updated 2026-10-12. Every number here was measured
on our bench unless it says *assumed*. Changes are logged at the end.

---

## 1. The idea

Boscon Space is flying a latex balloon to about 25–30 km over Maharashtra,
India, carrying a Raspberry Pi 5 that runs a small language model. From the
ground you send a prompt over a 866 MHz radio link; the payload answers from
the stratosphere, while a beacon reports where it is so we can get it back.
The mission is tracking-and-recovery; the LLM is the passenger
(`docs/01-mission-and-architecture.md`).

The software decision, made with JetBrains as a collaborator: **the payload
computer runs Kotlin, all the way down to the pins.** Not Kotlin as a shell
around Python and C, but one Kotlin/Native binary that owns the GPIO, the SPI
radio, the sensors, the data analysis, the LLM session and the flight log,
plus a Kotlin desktop console on the Mac that watches it. Two things are
deliberately not Kotlin: the independent recovery beacon's microcontroller
(C++ on a Heltec ESP32, because it must not share a software stack with the
LLM box, and because Kotlin/Native has no ESP32 target) and the matrix-maths
kernel of llama.cpp, which we link into the Kotlin binary rather than
rewrite.

## 2. What existed, and what we built

| Layer | Existed | We built (Kotlin unless noted) |
|---|---|---|
| Board | Raspberry Pi 5, 4 GB, Raspberry Pi OS Trixie (kernel 6.18) | first-boot config (cloud-init), partition/journal/fan setup scripts |
| Pin access | Linux kernel character devices: `/dev/gpiochip0` (GPIO v2 uAPI), `/dev/i2c-1`, `/dev/spidev0.0`, termios | **`hal/`**: direct `ioctl` bindings, no libgpiod, no Pi4J, no JVM. GPIO in/out/edge events, I²C transactions, SPI transfers, serial. The GPIO v2 structs are declared by us with compile-time size asserts because the toolchain's sysroot headers predate them |
| Radio | Semtech SX1262 inside a Heltec V3; RadioLib (C++) on the ESP32 | **`drivers/Sx1262`**: the GFSK configuration RadioLib 7.7.1 produces, reproduced register-for-register so our frames are received by the unchanged C++ receiver. **`BridgeBus`**: the Heltec as a dumb SPI/GPIO adapter over USB serial (`firmware/23_spi_bridge`, C++, ~250 lines, no radio logic) |
| Sensors | BMP280 on I²C (bench), more to come | **`drivers/Bmp280`** with the datasheet's integer compensation, unit-tested against Bosch's reference numbers; **`NmeaParser`** |
| Wire format | a 28-byte packed C struct in the firmware | **`protocol/TelemetryFrame`**: byte-identical, cross-checked against the C source by a script in CI-style (`tools/check_packet_abi.py`) |
| LLM | llama.cpp (C/C++), Qwen2.5-1.5B-Instruct Q4_K_M | **`llm/`**: cinterop binding; `Llm` wraps load (mlocked), ChatML prompt, greedy sampling, 128-token and 90-second guards |
| Agent | none | **`agent/`**: tool calling with a one-line `CALL <tool>` grammar a 1.5 B model follows; guards (busy, chip temperature, pack voltage) that refuse *before* the model is touched |
| Analysis | none | **`analysis/`**: rolling statistics, altitude-binned profiles, a Regener-Pfotzer peak detector for the radiation curve, burst and free-fall detection, stuck/rate/stale channel flags, honest prompt text ("unavailable", never invented) |
| Process | systemd | **`flightd/`**: the one process: 1 Hz tick → sensors → analysis → frame → beacon → synced NDJSON log → watchdog pet; agent on its own thread; sd_notify over a raw Unix socket |
| Ground side | none | **`station/`**: Compose for Desktop console on the Mac: pins, radio, charts, prompts, analysis, fan, code editor with build/flash/deploy, Wi-Fi, hardware watchdog. Reuses `protocol` and `analysis` as libraries |
| Ops | esptool, arduino-cli, Gradle | scripts to image the card, flash the Heltec *from the Pi* with rollback, deploy flightd, run every probe |

Code size on 2026-10-11, measured with `wc -l`: 2,814 lines of Kotlin across
9 modules, 118 lines of cinterop definitions, 166 lines of bridge C++, 333 lines
of shell tooling.

## 3. How Kotlin is integrated

**One Gradle Kotlin Multiplatform build, pinned to Kotlin 2.4.10.**
`linuxArm64` is a Tier 2 target (compiled on CI, not run-tested) so we freeze
the compiler for the 30-day test campaign. The Mac is the build host, because
Kotlin/Native cannot build on an aarch64 Linux host. A 650 KB to 2 MB
ELF is copied to the Pi over SSH.

**Logic in common Kotlin, I/O in thin native modules.** `protocol`,
`analysis` and `agent` are pure Kotlin with `linuxArm64`, `macosArm64` and
`jvm` targets. They are unit-tested on the Mac and reused unchanged by the
desktop console. The `hal`, `drivers`, `llm` and `flightd` modules are
`linuxArm64` and touch file descriptors.

**cinterop is the only bridge to C**, used three times, each with a lesson:

1. *Kernel interfaces* (`hal/src/nativeInterop/cinterop/linuxio.def`): the
   bundled sysroot is glibc 2.25 / kernel 4.9 headers, so the GPIO v2 ABI is
   declared in the `.def` itself, with `_Static_assert` on every struct size.
   `ioctl` request numbers are computed by the toolchain's own `_IOWR`, not
   typed by hand.
2. *llama.cpp* (`llm/.../llama.def`): headers and `.so` files come from a
   build done on the Pi, pulled to the Mac by a script. Opaque C structs land
   in `cnames.structs`; C enum values are top-level constants; linker flags
   belong in the `.def` so every executable that links the klib inherits
   them; Kotlin/Native drives `ld.lld` directly, so `--rpath=`, not `-Wl,`.
3. *A Unix datagram socket* for systemd's `READY=1` / `WATCHDOG=1`, because
   the bundled POSIX bindings lack `sockaddr_un`.

**Coroutines where blocking would hurt.** The beacon tick and the LLM share
a process; `flightd` runs the agent on `Dispatchers.Default` and the tick
on the main loop, so a 6-second model answer never delays a frame. Measured:
1000 ms ± 6 ms between frames while the model was answering.

## 4. How Kotlin is helping

- **One language from pin to pixel.** The same `TelemetryFrame` encodes on
  the Pi and decodes in the Mac console; the same `ChannelMonitor` flags a
  stuck sensor on board and in the replay view. There is no second
  implementation to drift.
- **Tests that run before hardware exists.** The BMP280 compensation, the
  NMEA parser, the frame layout, the radio register maths (bit-rate 26666,
  deviation 41943, frequency 908,171,648, the float32 values RadioLib
  produces), the agent's guard logic and the Pfotzer detector all pass on the
  Mac. Hardware probes then check the last mile, and each probe has a
  failing case we saw before the passing one.
- **A small, inspectable binary on a 4 GB board.** No JVM, no package tree
  on the read-only root we fly with. `flightd` is 1.97 MB and links libc,
  libm and libllama. Deployment is one `scp` and a `systemctl restart`;
  rollback is copying the previous file.
- **Type-safe I/O.** `GpioOutput`, `I2cBus`, `SpiBus`, `SerialPort`,
  `Sx1262Bus` are interfaces. The SX1262 driver does not know whether its
  bytes go through `spidev` on the Pi or through the Heltec bridge over USB;
  the flight article swaps one class.
- **Honest failure.** `HalException` carries the kernel call and `errno`;
  the agent returns `ERR_HOT` or `ERR_LOWBAT` instead of a guess; the
  analysis hands the model "uv index unavailable" rather than a number.

## 5. What we have achieved (all on the real board, 2026-10-11)

| Milestone | Result |
|---|---|
| Kotlin/Native runs on the Pi 5 | 655 KB probe reads the GPIO chip through our own kernel structs |
| GPIO, edges, SPI, I²C | loopback 20/20; 4,000 edge events at ~3 kHz with zero loss; 1280/1280 SPI bytes at 8 MHz; BMP280 found and read (938 hPa, 31 °C) |
| Radio from Kotlin | SX1262 reset, status and version read through the bridge; GFSK frames transmitted and received by the unchanged C++ receiver with its display |
| Continuous beacon | one 28-byte frame per second as a systemd service; survives a crash, a hang (30 s watchdog) and the bridge being unplugged and re-plugged |
| LLM in-process | Qwen2.5-1.5B, 11.9 tokens/s generation in Kotlin against 12.06 tokens/s for the C++ benchmark; deterministic; load 2.4 s |
| Agent | "What is the air pressure around you?" → `CALL read_sensors` → "The air pressure around me is 938.5 hPa." Live reading at that moment: 938.5 hPa. A tripped temperature guard refuses in 0 ms |
| Analysis | synthetic 0–30 km ascent: radiation peak found at 19 km ± 250 m; burst/free-fall state machine; channel flags |
| Station | nine live panels on the Mac; a prompt typed in the app is answered by the balloon computer |
| Ops | Heltec flashed *from the Pi* with automatic rollback, proven with a deliberately broken image |

What is **not** done: power during inference is unmeasured (needs an inline
USB-C meter); the prompt *uplink* over the radio is next (the file queue
stands in); the Geiger, UV, spectral and IMU sensors are on order; the chip's
version string reads "SX1261" and the +22 dBm assumption is unverified; the
24-hour soak of `flightd` is running.

## 6. How it all works now

```
Mac (HAB-1 Station, Compose Desktop) ── Wi-Fi/SSH ── Raspberry Pi 5 (4 GB, Trixie)
                                                       └─ flightd (Kotlin/Native, systemd, watchdog 30 s)
                                                            ├─ hal: /dev/gpiochip0, /dev/i2c-1, /dev/ttyUSB0
                                                            ├─ BMP280 → analysis → TelemetryFrame (28 B)
                                                            ├─ Sx1262 driver ── USB serial ── Heltec bridge (C++) ── SX1262 ── 866.1 MHz ──▶ RX Heltec (C++, unchanged)
                                                            ├─ llm: libllama in-process, Qwen2.5-1.5B mlocked
                                                            ├─ agent: prompt → CALL read_sensors → answer, guards first
                                                            └─ log: /log/flight/*.ndjson, O_SYNC, 10-min rotation
```

Prompts today enter as files in `~/prompts/` (from the station or `scp`);
answers land in `/log/answers/`. The radio uplink will feed the same queue.

## 7. How Kotlin developers can use this

**Reuse as libraries.** `hal/` is a generic Kotlin/Native HAL for any Linux
board with the GPIO character device, `i2c-dev` and `spidev`, so it runs on any Pi and
most SBCs. `drivers/Sx1262` works with any SX126x module. `analysis/` and
`agent/` are plain Kotlin and run on JVM, Native or desktop.

**Build it.**
```bash
# prerequisites on the Mac: JDK 21 (the build pins its toolchain to 21), Gradle 9, Kotlin/Native toolchain (downloads on first build)
git clone https://github.com/developerchunk/Kapex.git
cd Kapex
gradle :protocol:macosArm64Test :drivers:macosArm64Test :agent:macosArm64Test :analysis:macosArm64Test   # pure-Kotlin tests
gradle :probes:linkGpio_probeReleaseExecutableLinuxArm64   # one probe for the Pi
gradle :flightd:linkFlightdReleaseExecutableLinuxArm64     # the flight binary
gradle :station:run                                        # the Mac console
```
The `llm` module needs `tools/pi_llama_setup.sh` run once on a Pi (builds
llama.cpp there) and `tools/pi_llama_pull.sh` to fetch the headers and `.so`.

**Add a sensor.** Write a class in `drivers/` against `I2cBus` or `SpiBus`
(see `Bmp280`), put the maths in `commonMain` and test it on the Mac with the
datasheet's example, then add a probe in `probes/` whose pass condition can
fail, and wire the reading into `flightd` as a `Reading` with its
`ChannelMonitor` flags. The model sees it on the next `read_sensors`.

**Add a tool for the model.** `Tool("name", "what it returns") { ... }` in
the registry handed to `AgentLoop`. Keep results short and numeric; the
grammar is one line, `CALL name`, which Qwen2.5-1.5B follows from a single
system-prompt sentence.

**Run it on your own Pi.** `tools/image_card.sh`, `tools/boot_config.sh`
(cloud-init: note the `instance-id` hyphen and `enable_ssh`),
`tools/pi_firstboot_setup.sh`, then `tools/pi_flightd.sh install`. The
bench history, every dead end included, is in `04-worklog.md`.

**Known Kotlin/Native pitfalls we hit, so you don't:** forward-declared C
structs live in `cnames.structs`; `String.format` does not exist on Native;
`assert()` is experimental, use `kotlin.test`; a `@Composable` call cannot
sit inside `remember { }`; linker options go in the cinterop `.def`;
`-Wl,` is wrong because `ld.lld` is called directly.

---

### Changelog of this document
- 2026-10-20: camera. A Logitech C270 is read through V4L2 from Kotlin (mmap
  streaming, the kernel header straight from the sysroot) and its MJPEG frames
  are written to AVI segments by a 200-line Kotlin writer, Huffman tables
  inserted so strict players open them. Tested on the JVM against a real
  frame; one-minute clip verified on the board and decoded by AVFoundation.
- 2026-10-11: first version, after the first bench day: M0–M3, M5, M6, M7a,
  M8, M9, M11, M16 passed; M12 soaking.
- 2026-10-12: the project is named Kapex. The folder `payload/` became `kapex/`, the
  Gradle root project is `kapex`, and the Kotlin packages moved from
  `space.boscon.hab` to `space.boscon.kapex`. No behaviour changed.
- 2026-10-13: build steps start from `git clone` of the public repository; JDK prerequisite
  corrected to 21; code counts for the page recounted (2,814 lines of Kotlin, 349 lines of shell).
