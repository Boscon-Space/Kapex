#!/bin/bash
# One-time setup on the Pi after first boot of the Trixie Lite image.
# Run from the Mac:
#   scp -i ~/.ssh/hab1_pi payload/tools/pi_firstboot_setup.sh hab@hab1.local:
#   ssh -i ~/.ssh/hab1_pi hab@hab1.local sudo bash pi_firstboot_setup.sh
#
# What it does, in order, each step idempotent:
#   1. packages: parted, build tools for llama.cpp, i2c/spi/gpio userland
#   2. partitions: root grows to 8 GiB, then /log (ext4, sync) 4 GiB
#   3. journald persistent (bench), no swap
#   4. enable I2C, SPI, UART in config.txt
#   5. read-only root via overlayfs is NOT enabled here — it is the last
#      step before flight (tools/pi_lock_rootfs.sh), so the bench stays writable
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
DEV=/dev/mmcblk0
ROOT=${DEV}p2
LOG=${DEV}p3

echo "== 1. packages"
if curl -sS -m 8 -o /dev/null https://deb.debian.org/ 2>/dev/null; then
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
  parted git build-essential cmake pkg-config \
  i2c-tools gpiod libgpiod-dev spi-tools python3-smbus \
  rpicam-apps-lite v4l-utils \
  htop
else
  echo "!! no internet on the Pi (enable Internet Sharing on the Mac to the Pi's USB interface); packages skipped, re-run later"
fi

echo "== 2. partitions"
if [ ! -b "$LOG" ]; then
  # sfdisk works on a mounted root where parted refuses: grow p2 to 8 GiB,
  # tell the kernel, grow the filesystem, then append p3 (4 GiB) for /log
  echo ",8GiB" | sfdisk --no-reread --force -N 2 "$DEV"
  partx -u "$DEV" || true
  resize2fs "$ROOT"
  echo ",4GiB,L" | sfdisk --no-reread --force -a "$DEV"
  partx -a "$DEV" 2>/dev/null || partprobe "$DEV" || true
  sleep 1
  [ -b "$LOG" ] || { echo "!! $LOG did not appear"; exit 1; }
  mkfs.ext4 -q -L hab1log "$LOG"
fi
mkdir -p /log
grep -q "LABEL=hab1log" /etc/fstab || echo "LABEL=hab1log /log ext4 defaults,noatime,sync,nofail 0 2" >> /etc/fstab
mount -a
chown hab:hab /log
lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINT "$DEV"

echo "== 3. journald persistent (bench), no swap"
mkdir -p /etc/systemd/journald.conf.d /var/log/journal
printf '[Journal]\nStorage=persistent\nSystemMaxUse=64M\n' > /etc/systemd/journald.conf.d/hab1.conf
systemctl disable --now dphys-swapfile 2>/dev/null || true
swapoff -a || true

echo "== 4. config.txt interfaces"
CFG=/boot/firmware/config.txt
for line in "dtparam=i2c_arm=on" "dtparam=spi=on" "enable_uart=1" "dtparam=uart0=on"; do
  grep -q "^$line" "$CFG" || echo "$line" >> "$CFG"
done
# free the UART from the serial console for the GPS
sed -i 's/console=serial0,115200 //' /boot/firmware/cmdline.txt

echo "== 5. versions (paste into docs/35 §7 step 1)"
uname -r; ldd --version | head -1; dpkg -s libgpiod3 2>/dev/null | grep ^Version || dpkg -l 'libgpiod*' | grep ^ii
gpiodetect
free -m
echo "reboot now: sudo reboot"
