# Milestones

Status: ☐ not started · ◐ in progress · ☑ passed on the board · ✗ failed (kept until fixed)

| # | Milestone | Pass condition | Status | Date |
|---|---|---|---|---|
| M0 | Bench Pi reachable, set up | SSH as `hab`, root 8 GiB, `/log` mounted, internet | ☑ | 2026-10-11 |
| M1 | Kotlin/Native binary runs on the Pi | `gpio_probe` prints chip label `pinctrl-rp1` | ☑ | 2026-10-11 |
| M2 | GPIO verified | loopback 20/20 with jumper, FAIL without it (deliberately break it once) | ☑ failed 10/20 without jumper, passed 20/20 with it | 2026-10-11 |
| M3 | I²C verified | scan finds the bench BMP280, id 0x58 | ☑ failed (errno 121) with nothing wired; then 0x76 found, id 0x58, 10 readings 937.71 hPa / 31.5 °C, spread 0.017 hPa | 2026-10-11 |
| M4 | MS5611 driver | pressure within ±2 hPa of the Heltec's BMP280 on the same desk | ☐ | |
| M5 | Edge counting | GPIO edge events counted at ≥ 1 kHz from a signal source without loss | ☑ 2000 pulses → 2000 rising + 2000 falling, 0 seqno gaps, at 1 kHz and ~3 kHz effective (`edge_probe`) | 2026-10-11 |
| M6a | spidev verified | MOSI→MISO loopback 1280/1280 at 8 MHz with jumper, FAIL without | ☑ FAIL 1279/1280 without jumper, PASS 1280/1280 at 1/4/8 MHz with it | 2026-10-11 |
| M6 | SPI + SX1262 over Heltec bridge | Kotlin driver reads SX1262 silicon version via the bridge | ☑ ping, reset→BUSY low, GetStatus 0x2A STDBY_RC, version "SX1261 V2D 2D02" (`radio_probe`); failed first without the bridge attached | 2026-10-11 |
| M7a | Telemetry downlink from Kotlin | 09_telemetry_rx receives frames sent by the Pi through the bridge | ☑ 60/60 TX_DONE, received and displayed on the RX TFT (Aditya's observation); failed first on the status-byte bugs | 2026-10-11 |
| M7 | Mission link round trip | 20/20 prompt→answer round trips on 867.3 MHz GFSK | ☐ | |
| M8 | llama.cpp in-process | Qwen2.5-1.5B ≥ 8 tok/s, power measured at USB-C | ☐ | |
| M9 | Agent loop | prompt → `read_sensors` tool → answer, with guards exercised (one refused) | ☐ | |
| M10 | Sensor suite drivers | AS7331, AS7341, BNO085, Geiger each with a probe that can fail | ☐ | |
| M11 | Analysis module | unit tests on the Mac; Pfotzer peak found in a synthetic profile | ☐ | |
| M12 | `flightd` as a systemd service | survives 24 h soak, watchdog restart demonstrated | ☐ | |
| M13 | Camera capture to USB drive | 2 streams, 60 s segments, brownout loses ≤ 60 s | ☐ | |
| M14 | Kotlin/Native go/no-go recorded | all of M1–M9 pass; else JVM fallback decision | ☐ | |
| M15 | Flight lock-down | read-only root, journald volatile, model mlocked, cold test passed | ☐ | |
