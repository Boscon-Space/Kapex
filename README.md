# HAB-1 payload software (Kotlin/Native, Raspberry Pi 5)

One static Kotlin/Native binary runs the payload. See
`../docs/35-pi5-kotlin-payload-computer.md` §8 for the plan and §9 for the
bench bring-up log.

| Path | What |
|---|---|
| `hal/` | Kernel-interface HAL: GPIO chardev v2, i2c-dev, spidev, termios. Links libc only. |
| `probes/` | Bench probes, one executable each, pass conditions that can fail. |
| `tools/image_card.sh` | Write the OS image to a microSD on the Mac. |
| `tools/boot_config.sh` | Put the cloud-init first-boot files on an imaged card. |
| `tools/pi_firstboot_setup.sh` | Run once on the Pi: partitions, packages, journald, interfaces. |
| `tools/pi.sh` | ssh/scp wrapper (default `hab1.local`; `PI_HOST=10.12.194.1` on the USB gadget link). |
| `tools/run_probe.sh` | Build a probe, copy it to the Pi, run it. |
| `image/` | Downloaded image + per-machine `user-data`/`network-config` (git-ignored). |

Build host: the Mac (Kotlin/Native cannot build on an aarch64 Linux host).
Kotlin is pinned to 2.4.10 for the test campaign (`gradle.properties`).

```bash
gradle :probes:linkGpio_probeReleaseExecutableLinuxArm64   # one probe
tools/run_probe.sh gpio_probe                      # build + run on the Pi
```
