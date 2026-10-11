# Findings — verified facts, and what is not verified

Last updated: 2026-10-11. "Verified" = observed on the board or measured here.

## The board (verified over SSH, 2026-10-11)

| Fact | Value | How |
|---|---|---|
| Model / RAM | Raspberry Pi 5, 4049 MB total | `free -m` |
| OS | Raspberry Pi OS Trixie Lite 64-bit, image 2026-10-06 | `issue.txt` |
| Kernel / glibc | 6.18.50+rpt-rpi-2712 / 2.41 | `uname -r`, `ldd --version` |
| Header GPIO chip | `/dev/gpiochip0`, label `pinctrl-rp1`, 54 lines | `gpiodetect` |
| Other chips | gpiochip10–13, `gpio-brcmstb` (SoC internals, not the header) | `gpiodetect` |
| libgpiod (bench tools only) | 2.2.1-2+deb13u1 | apt |
| Bootloader EEPROM | 2025-08-28; update available, **not applied** | `rpi-eeprom-update` |
| Card | Samsung EVO Plus 32 GB; p1 512 MB boot, p2 8 GiB root, p3 4 GiB `/log` | `lsblk` |
| Network | Wi-Fi on the Pixel 6 hotspot, `hab1.local`, 10.18.98.33 at the time; internet works | ping, curl |
| USB gadget | `dwc2` binds `g_ether`, `usb0` exists on the Pi | `dmesg` |
| Mac side of USB gadget | **never enumerates** (two controllers, zero devices), with a cable verified by a Pixel 6a | `ioreg` |

## Image / cloud-init behaviour (verified by reading the card)

- The 2026-10-06 image configures first boot with **cloud-init** (`user-data`,
  `meta-data`, `network-config` on the FAT boot partition). `custom.toml` is
  not consumed.
- `rpi:` module keys that worked: `interfaces.i2c/spi/serial`,
  `enable_usb_gadget`. `enable_ssh: true` is documented by Raspberry Pi and
  is now in our `user-data`; the empty `ssh` file on the boot partition also
  works (`sshswitch.service`). Without one of these **sshd is not running**.
- `meta-data` ships `instance_id:` (underscore). cloud-init reads
  **`instance-id`** (hyphen). The shipped key does nothing: the instance is
  always "nocloud", so a changed `network-config` is never applied. Writing
  `instance-id: <new>` makes the next boot re-run first-boot (idempotent).
- `enable_usb_gadget` runs `rpi-usb-gadget on -f` then `shutdown -r now`.
- Raspberry Pi OS Lite keeps the journal **volatile** by default even though
  `/var/log/journal` exists. `Storage=persistent` via `write_files` fixed it.
- The image's kernel command line carries a bare `resize` token; removing it
  plus `growpart: mode: off` kept root at image size (2.4 GB) as intended.
- `parted resizepart` refuses on a mounted root non-interactively; `sfdisk
  --no-reread --force -N 2` + `partx -u` + `resize2fs` works.
- `debugfs` (Homebrew `e2fsprogs`) reads the ext4 root from macOS with sudo.

## Kotlin toolchain (verified on the Mac)

- Kotlin/Native prebuilt 2.4.10 was already in `~/.konan`; Gradle 9.8.1 from
  Homebrew ships Kotlin 2.4.10 embedded. Wrapper also installed (9.8.1).
- The linuxArm64 sysroot (`aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2`,
  verified from the download name) has kernel 4.9 headers, so GPIO uAPI v2
  structs are declared in our own cinterop `.def` with `_Static_assert` on
  sizes; the asserts compiled and `GPIO_GET_CHIPINFO` returned correct data
  on the board, so the layouts match the running 6.18 kernel.
- GPIO v2 edge events via blocking `read()` on the line fd: 4000 edges at up
  to ~3 kHz with zero loss on the stock kernel, no realtime tuning (measured,
  `edge_probe`). Geiger rates (tens of cps at the Pfotzer peak) are three
  orders of magnitude below this.
- Probe binary: 655 KB, dynamically linked against glibc and friends only;
  runs on the stock image with no extra packages. (RSS not yet measured.)
- Named executable task: `linkGpio_probeReleaseExecutableLinuxArm64`.

## Driver maths verified on the Mac

- BMP280 datasheet integer compensation, ported 1:1: reproduces the Bosch
  reference example (calibration T1=27504 … P9=6000, adcT=519888,
  adcP=415148 → 25.08 °C, 100653 Pa) in `Bmp280Test`. The flight barometer is
  the MS5611; this driver is for the borrowed bench sensor.

## Numbers from research (not measured by us)

| Item | Value | Source |
|---|---|---|
| Pi 5 8 GB idle / all-cores | 3.2 W / 9.8 W at USB-C | Jeff Geerling |
| Pi 5 1080p30 x264 low-latency | ~60 % of one core | Raspberry Pi white paper RP-010033 |
| Qwen2.5-1.5B Q4 on Pi 5 | ~9–10 tok/s (Ollama) | Stratosphere lab |
| Moondream on Pi 5 | 8–90 s per image | Core Electronics |
| Geiger count rate 0 → 19 km | ~0.2 → ~14 cps (70×) | Taylor Univ. flight |
| ADS-B horizon from 30 km | ~620 km computed; 850 km reported | OpenSky 2019 |

## Power (verified 2026-10-11)

- Pi 5 powered from the Mac's USB-C port **browned out and rebooted** when
  the Heltec bridge was plugged into a USB-A port: `hwmon: Undervoltage
  detected!`, `vcgencmd get_throttled` = 0x50000 (under-voltage occurred +
  throttling occurred). The Mac port is adequate for the bare board only;
  use the official 5 V / 5 A supply once anything hangs off USB.
- On the official supply with the Heltec attached: `throttled=0x0`, input
  5.14 V at the PMIC (`vcgencmd pmic_read_adc`, EXT5V_V), no under-voltage
  events across a fresh boot; `radio_probe` ALL PASS again.
- Heltec V3 on the Pi enumerates as `cp210x` → `/dev/ttyUSB0`, group
  `dialout` (user `hab` is a member).

## Radio (verified 2026-10-11)

- SX126x on the TX Heltec, reached from Kotlin on the Pi through the bridge:
  reset, GetStatus (0x2A = STDBY_RC, command status OK), register reads all
  behave per the datasheet. Round trip per SPI transfer over USB-CDC not yet
  timed.
- **Version string at 0x0320 reads "SX1261 V2D 2D02"** on a board sold as
  SX1262. RadioLib users have reported the same string on SX1262 modules, so
  the string alone does not settle which die this is — but it matters:
  SX1261 tops out at +15 dBm, SX1262 at +22 dBm, and every power figure in
  docs/14, 15, 19 assumes +22 dBm. Needs a test that can fail: command
  +22 dBm and measure, or check Heltec's schematic/part marking.

## Not verified / open

- Why the Mac never enumerates the Pi in gadget mode (EEPROM update pending
  is the first thing to try).
- Actual power during inference, binary RSS, model load time, tokens/s on
  *this* board — all still assumed.
- BMP280 rated range 300–1100 hPa, MS5611 10–1200 mbar — recalled, check the
  datasheets before the BOM is final.
