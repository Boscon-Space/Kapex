#!/bin/bash
# Runs ON THE PI. Flash the Heltec bridge over /dev/ttyUSB0 with the images in
# ~/bridge/new, with a way out: if the new firmware does not answer the ping,
# the images in ~/bridge/last-good are flashed back. The beacon service is
# stopped for the duration (it owns the port) and restarted at the end.
set -uo pipefail
PORT=${PORT:-/dev/ttyUSB0}
NEW=$HOME/bridge/new; GOOD=$HOME/bridge/last-good
ESPTOOL=$(command -v esptool || command -v esptool.py)
FILES="0x0 bootloader.bin 0x8000 partitions.bin 0xe000 boot_app0.bin 0x10000 app.bin"

flash() {   # flash <dir>
  local d=$1 args=""
  for f in bootloader.bin partitions.bin boot_app0.bin app.bin; do [ -f "$d/$f" ] || { echo "missing $d/$f"; return 1; }; done
  set -- $FILES; while [ $# -gt 0 ]; do args="$args $1 $d/$2"; shift 2; done
  $ESPTOOL --no-stub --chip esp32s3 --port "$PORT" --baud 460800 --before default_reset --after hard_reset \
    write_flash -z --flash_mode keep --flash_freq keep --flash_size keep $args 2>&1 | tail -3
  return ${PIPESTATUS[0]}
}

ping_bridge() {   # the ESP32 reboots on port open; drain, then expect pHAB1
  python3 - "$PORT" <<'PY'
import os, sys, termios, select, time
fd = os.open(sys.argv[1], os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
a = termios.tcgetattr(fd); a[0]=0; a[1]=0; a[2]=termios.CS8|termios.CREAD|termios.CLOCAL; a[3]=0; a[4]=a[5]=termios.B921600
termios.tcsetattr(fd, termios.TCSANOW, a); time.sleep(2.5); termios.tcflush(fd, termios.TCIOFLUSH)
for _ in range(4):
    os.write(fd, b'P'); got=b''; t=time.time()+0.5
    while time.time()<t and len(got)<5:
        r,_,_=select.select([fd],[],[],0.1)
        if r:
            try: got+=os.read(fd,64)
            except BlockingIOError: pass
    if got.startswith(b'pHAB1'): sys.exit(0)
sys.exit(1)
PY
}

# whichever service owns the port must be stopped first; flashing while a
# process talks on /dev/ttyUSB0 erases the app and fails the write (2026-10-11)
WAS=""; for s in hab1-flightd hab1-telemetry; do systemctl is-active --quiet $s.service && WAS="$WAS $s"; done
echo "== stopping$WAS"; sudo systemctl stop hab1-flightd.service hab1-telemetry.service
sudo fuser -k /dev/ttyUSB0 2>/dev/null; sleep 1
if sudo fuser /dev/ttyUSB0 >/dev/null 2>&1; then echo "!! /dev/ttyUSB0 still busy; refusing to flash"; sudo systemctl start ${WAS:-hab1-flightd}.service; exit 4; fi
echo "== flashing new"
if flash "$NEW" && ping_bridge; then
  echo "== new bridge answers; promoting to last-good"; cp "$NEW"/*.bin "$GOOD"/; rc=0
else
  echo "!! new bridge did not answer"
  if [ -f "$GOOD/app.bin" ]; then
    echo "== flashing last-good back"
    if flash "$GOOD" && ping_bridge; then echo "== last-good restored and answering"; rc=2; else echo "!! last-good did not answer either — bridge needs the Mac"; rc=3; fi
  else echo "!! no last-good image yet"; rc=3; fi
fi
SVC=hab1-flightd; echo "$WAS" | grep -q telemetry && ! echo "$WAS" | grep -q flightd && SVC=hab1-telemetry
echo "== starting $SVC"; sudo systemctl start $SVC.service; sleep 12
systemctl is-active $SVC.service; journalctl -u $SVC.service -n 3 --no-pager -o cat | grep -E "^radio|^#|FATAL" | tail -2
exit $rc
