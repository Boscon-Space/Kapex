#!/bin/bash
# Write the Raspberry Pi OS image in payload/image/ to the microSD card and
# drop custom.toml onto its boot partition.
#
#   payload/tools/image_card.sh            # lists candidate disks, asks
#   payload/tools/image_card.sh /dev/disk4 # writes to that disk, after confirm
#
# Refuses anything that is not an external, physical, removable disk under
# 64 GB. Still: read the diskutil output it prints before typing YES.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
IMG=$(ls "$HERE"/image/*-raspios-*-arm64-lite.img.xz | tail -1)
TOML="$HERE/image/custom.toml"
[ -f "$IMG" ]  || { echo "no image in $HERE/image"; exit 1; }
[ -f "$TOML" ] || { echo "no custom.toml in $HERE/image"; exit 1; }

if [ $# -eq 0 ]; then
  echo "External physical disks:"; diskutil list external physical
  echo; echo "re-run with the disk, e.g. $0 /dev/disk4"; exit 0
fi
DISK=$1
INFO=$(diskutil info "$DISK")
echo "$INFO" | grep -q "Device Location: *External" || { echo "$DISK is not external"; exit 1; }
echo "$INFO" | grep -q "Virtual: *No"               || { echo "$DISK is virtual"; exit 1; }
BYTES=$(echo "$INFO" | sed -n 's/.*Disk Size:.*(\([0-9]*\) Bytes).*/\1/p')
[ -n "$BYTES" ] && [ "$BYTES" -lt 68719476736 ] || { echo "$DISK is $BYTES bytes, not a <64 GB card"; exit 1; }

echo "$INFO" | grep -E "Device Node|Device / Media Name|Disk Size|Protocol|Removable"
echo; echo "ALL DATA ON $DISK WILL BE DESTROYED. Image: $(basename "$IMG")"
read -r -p "type YES to continue: " ok; [ "$ok" = "YES" ] || exit 1

diskutil unmountDisk "$DISK"
RAW=${DISK/disk/rdisk}
xz -dc "$IMG" | sudo dd of="$RAW" bs=4m status=progress
sync
diskutil mountDisk "$DISK"
BOOT=$(diskutil info "${DISK}s1" | sed -n 's/.*Mount Point: *//p')
[ -d "$BOOT" ] || { echo "boot partition did not mount"; exit 1; }
cp "$TOML" "$BOOT/custom.toml"
# keep root at image size; tools/pi_firstboot_setup.sh partitions the rest (log + spare)
sed -i '' 's| init=/usr/lib/raspberrypi-sys-mods/firstboot||' "$BOOT/cmdline.txt" || true
echo "boot files:"; ls "$BOOT" | head; cat "$BOOT/cmdline.txt"
diskutil eject "$DISK"
echo "done. Put the card in the Pi and power it; hostname hab1, user hab, ssh -i ~/.ssh/hab1_pi hab@hab1.local"
