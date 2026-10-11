# Decision log

Newest first. Each entry: date, decision, why, what it rules out.

## 2026-10-11

- **GPS will connect to the Pi 5 directly** (UART on header pins 8/10,
  `/dev/ttyAMA0`, already enabled), not through the Heltec bridge. Why:
  Aditya's call; the flight article has the MAX-M10S on the Pi anyway (docs/04
  §5.2). The bridge's GPS forwarding stays as a bench convenience. Bench GPS
  parked for now (indoors, and not currently driving any Heltec pin).
- **Bench network is the phone hotspot (Wi-Fi), not USB gadget.** Why: USB
  gadget mode works on the Pi side but the Mac never enumerates the Pi
  (unresolved, see findings). Wi-Fi also gives the Pi internet for packages.
  Rules out: nothing; gadget mode stays enabled and is revisited later.
- **Video storage: separate USB drive, 60 s segments, fsync on close.** Why:
  no NVMe; the boot card must stay idle to survive brownout. Rules out:
  recording video to the boot card.
- **Cameras: buy USB UVC cameras with on-board H.264.** Why: the Pi 5 has no
  hardware H.264 encoder; two CSI 720p streams cost ~0.7 core continuously
  (computed from Raspberry Pi's 1080p30 figure) and compete with inference.
  UVC H.264 also makes capture pure Kotlin via V4L2. Cameras not yet bought.
- **Samsung EVO Plus 32 GB is the bench card only.** Why: consumer card,
  measured collapsing under sustained writes in reviews; flight uses the BOM's
  high-endurance A2 cards.
- **Mission radio on the bench: Heltec as a dumb SPI/GPIO bridge over USB
  serial; Kotlin SX1262 driver runs on the Pi.** Why: only two Heltecs in
  hand, no bare SX1262; the Heltec's radio is captive to its ESP32. Flight:
  order an E22-900M30S, same Kotlin driver over spidev. Rules out: a C++
  modem firmware that owns the radio logic.
- **Sensor batch 1 confirmed:** SBM-20 board, AS7331, AS7341, BNO085, MS5611.
- **llama.cpp is the linked-in maths kernel.** Accepted by Aditya. Pure-Kotlin
  inference is a stretch goal after the flight stack works.
- **One Kotlin/Native binary, custom zero-dependency HAL, no Koog, no JVM.**
  Why: Aditya's direction — full end-to-end Kotlin, very lightweight, build
  custom where nothing fits. Overrides the first-draft JVM recommendation.
  JVM is the fallback only if Native fails a go/no-go. Beacon stays C++.
- **Kotlin pinned to 2.4.10 for the 30-day campaign.** Why: linuxArm64 is a
  Tier 2 target; a compiler update mid-campaign could break the build.
- **Board is a Pi 5 with 4 GB, microSD, no NVMe** (docs corrected from 8 GB
  / NVMe).

## Earlier (from docs/01, 04, 14, 17)

- Three independent chains; the beacon never shares a battery, processor,
  antenna or software with the LLM box.
- Radio limits from the gazette text (G.S.R. 853(E)): ≤ 200 kHz, 2.5 % duty
  per device, 500 mW e.r.p.; BW250 LoRa is not available.
