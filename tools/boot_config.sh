#!/bin/bash
# Put the first-boot files on an already-imaged card's boot partition.
#   kapex/tools/boot_config.sh            # uses /Volumes/bootfs
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
BOOT=${1:-/Volumes/bootfs}
[ -f "$BOOT/cmdline.txt" ] || { echo "$BOOT is not a Raspberry Pi boot partition"; exit 1; }
[ -f "$BOOT/user-data" ]   || { echo "$BOOT has no user-data: not a cloud-init image"; exit 1; }
cp "$HERE/image/user-data" "$BOOT/user-data"
if [ -f "$HERE/image/network-config" ]; then
  grep -q "PUT-YOUR-WIFI" "$HERE/image/network-config" && { echo "network-config still has placeholders; fill it in first"; exit 1; }
  cp "$HERE/image/network-config" "$BOOT/network-config"
  # cloud-init applies network-config only for a new instance: bump the id so
  # the next boot re-runs first-boot config (idempotent) with the new network
  # cloud-init's NoCloud key is 'instance-id' (hyphen); the image's meta-data
  # only has 'instance_id', which cloud-init ignores (instances dir = "nocloud").
  ID="hab1-$(date +%Y%m%d%H%M%S)"
  sed -i '' -E '/^instance[-_]id: /d' "$BOOT/meta-data"
  printf 'instance_id: %s\ninstance-id: %s\n' "$ID" "$ID" >> "$BOOT/meta-data"
  grep -E "^instance[-_]id" "$BOOT/meta-data"
fi
rm -f "$BOOT/custom.toml"
# Raspberry Pi OS enables sshd at boot when this file exists (sshswitch.service), then deletes it
touch "$BOOT/ssh"
# the image's cmdline carries a bare 'resize' token; assumed to trigger root
# expansion in the initramfs. Remove it so the log partition can be created.
sed -i '' -E 's/ resize$//; s/ init=\/usr\/lib\/raspberrypi-sys-mods\/firstboot//' "$BOOT/cmdline.txt"
echo "cmdline: $(cat "$BOOT/cmdline.txt")"
echo "user-data head:"; grep -E "^hostname|^- name|enable_usb_gadget|mode:" "$BOOT/user-data"
sync
diskutil eject "$(df "$BOOT" | awk 'NR==2{print $1}' | sed 's/s[0-9]*$//')"
echo "ejected. Next: card into the Pi, USB-C cable Pi <-> Mac, wait ~90 s, then:"
echo "  ssh -i ~/.ssh/hab1_pi hab@10.12.194.1    (or hab@hab1.local)"
