#!/bin/bash
# Build firmware/23_spi_bridge on the Mac, push the images to the Pi, and let
# the Pi flash its own Heltec (tools/pi_bridge_flash.sh). The Heltec never
# comes back to the Mac. Exit codes: 0 new firmware running, 2 rolled back to
# last-good, 3 bridge not answering.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd); ROOT=$(cd "$HERE/.." && pwd)
FQBN=esp32:esp32:heltec_wifi_lora_32_V3
SKETCH=$ROOT/firmware/23_spi_bridge
OUT=$HERE/build/bridge
mkdir -p "$OUT"
arduino-cli compile --fqbn $FQBN --output-dir "$OUT" "$SKETCH" 2>&1 | tail -1
cp "$(ls -d ~/Library/Arduino15/packages/esp32/hardware/esp32/*/tools/partitions/boot_app0.bin | tail -1)" "$OUT/boot_app0.bin"
cp "$OUT/23_spi_bridge.ino.bootloader.bin" "$OUT/bootloader.bin"
cp "$OUT/23_spi_bridge.ino.partitions.bin" "$OUT/partitions.bin"
cp "$OUT/23_spi_bridge.ino.bin" "$OUT/app.bin"
ls -l "$OUT"/{bootloader,partitions,boot_app0,app}.bin | awk '{print $5, $9}'
for f in bootloader partitions boot_app0 app; do "$HERE/tools/pi.sh" scp "$OUT/$f.bin" "hab1:/home/hab/bridge/new/$f.bin"; done
"$HERE/tools/pi.sh" scp "$HERE/tools/pi_bridge_flash.sh" hab1:/home/hab/bridge/flash.sh
"$HERE/tools/pi.sh" 'chmod +x ~/bridge/flash.sh && ~/bridge/flash.sh'
