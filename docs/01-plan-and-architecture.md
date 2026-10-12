# Plan and architecture

Last updated: 2026-10-11

## Goal

A Raspberry Pi 5 (4 GB) flies in the HAB-1 payload running a local LLM. The
Pi-side software is **one Kotlin/Native binary**, `flightd`, written by us
(JetBrains collaboration: Kotlin everywhere it honestly can be). It owns the
pins, the mission radio, the sensors, the LLM handling, and the on-board data
analysis. Only two things are not Kotlin: the Chain-2 beacon (Heltec, C++, by
Aditya's explicit decision) and the llama.cpp maths kernel, which is linked
into the Kotlin binary.

## System

```
BALLOON ── chute ── Box A "brain" (Pi 5 4 GB, Kotlin flightd, Chain-1 radio, sensors, cameras)
                 └─ Box B "lifeline" (Heltec V3 + GPS, Chain-2 beacon, C++, own battery) — untouched by the Pi
```

Radio plan (`docs/17`): Chain 2 beacon LoRa SF9 BW125 on 866.1 MHz, one
packet per 12 s. Chain 1 mission link GFSK 38.4 kb/s on 867.3 MHz, prompts up
and answers + telemetry down, 2.5 % duty cycle per device under G.S.R. 853(E).

## Software on the Pi

```
systemd ──┬── flightd (Kotlin/Native, one process, coroutines)
          │     ├─ hal/       GPIO chardev v2, i2c-dev, spidev, termios — libc only, no libgpiod/Pi4J/JVM
          │     ├─ drivers/   sx1262, ms5611, as7331, as7341, bno085, geiger (edge counter), mlx90614
          │     ├─ protocol/  28-byte telemetry frame, prompt chunk/ACK/NACK, HMAC tag (common Kotlin)
          │     ├─ analysis/  altitude bins, rolling stats, Pfotzer peak, burst/free-fall, anomaly flags (common)
          │     ├─ llm/       cinterop to libllama: load, tokenize, sample, 128-token cap, 90 s timeout
          │     ├─ agent/     ToolRegistry + loop (prompt → ≤3 tool calls → answer), guards (common)
          │     ├─ camera/    rpicam-vid / V4L2 capture to a separate USB drive, 60 s segments
          │     └─ log/       NDJSON on /log (ext4, sync), O_SYNC per record, 10-min rotation
          └── watchdog: systemd WatchdogSec=30 on the Pi hardware watchdog; flightd pets it
```

Repository layout (`kapex/`, Gradle Kotlin Multiplatform):
`hal/` (linuxArm64 + macosArm64 for host tests), `probes/` (one executable
per bench probe), later `drivers/`, `protocol/`, `analysis/`, `llm/`,
`agent/`, `analysis/`, `flightd/`, `station/` (HAB-1 Station: the Mac console, Compose Desktop, reads the Pi over SSH).

Build host is the Mac; Kotlin/Native cannot build on an aarch64 Linux host.
Logic lives in common modules so it is unit-tested on the Mac; linuxArm64
modules are thin I/O (Tier 2 target, cannot run its own tests).

## Memory budget (4 GB board)

| Resident | Size | Basis |
|---|---|---|
| OS, headless Lite | ~0.3–0.5 GB | assumed; measure |
| flightd | ~30 MB | assumed; measure |
| Qwen2.5-1.5B-Instruct Q4_K_M, mlocked | ~1.1 GB | docs/01 §5.1 |
| KV cache, ctx 512 | ~50 MB | computed order of magnitude |
| Vision model 256–500 M, Q8 | ~0.3–0.6 GB | assumed |
| Gemma-3-270M limp-home | ~0.3 GB | docs/01 §5.1 |
| **Headroom** | **~1.4–1.9 GB** | — |

Excluded by the 4 GB: any 3 B model, any 2 B vision model. No swap.

## Storage

microSD boot card (bench: Samsung EVO Plus 32 GB; flight: high-endurance A2
per BOM row 14). Read-only root before flight, `/log` partition synced, model
mlocked so the card is idle in flight, journal volatile in flight. Video from
two 720p cameras goes to a **separate USB drive**, never the boot card.

## Sensors (ranked, docs/35 §5)

Core science: SBM-20 Geiger (Pfotzer maximum), AS7331 UV A/B/C, AS7341
spectral sky colour, BNO085 IMU, MS5611 barometer. Demo: Camera Module 3 +
small vision model. Stretch: I²S microphone, RTL-SDR ADS-B, MLX90640 thermal.
Not flown: fan particulate sensors, NDIR CO₂, MOX gas, capacitive humidity.
