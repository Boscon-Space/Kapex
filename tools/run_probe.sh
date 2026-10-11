#!/bin/bash
# Build a probe for the Pi, copy it over, run it, show the result.
#   tools/run_probe.sh gpio_probe [args...]
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
NAME=${1:?probe name, e.g. gpio_probe}; shift
cd "$HERE"
# Gradle task name capitalises the binary name: gpio_probe -> linkGpio_probeReleaseExecutableLinuxArm64
TASK="link$(echo "${NAME:0:1}" | tr a-z A-Z)${NAME:1}ReleaseExecutableLinuxArm64"
gradle -q ":probes:$TASK" 2>&1 | grep -v "^$" || true
BIN="$HERE/probes/build/bin/linuxArm64/${NAME}ReleaseExecutable/${NAME}.kexe"
[ -f "$BIN" ] || { echo "no binary at $BIN"; exit 1; }
echo "binary: $(du -h "$BIN" | cut -f1) $(file -b "$BIN" | cut -d, -f1-2)"
"$HERE/tools/pi.sh" scp "$BIN" "hab1:/home/hab/$NAME"
"$HERE/tools/pi.sh" "chmod +x ~/$NAME && ~/$NAME $*"
