#!/bin/bash
# Run every bench probe already staged on the Pi, each with its own arguments.
#   tools/run_all_probes.sh            # verdict line per probe
#   tools/run_all_probes.sh -v         # full output
set -uo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
V=${1:-}; export V
"$HERE/tools/pi.sh" "$(cat <<'REMOTE'
run() { name=$1; shift; out=$(~/$name "$@" 2>&1); rc=$?; if [ -n "$V" ]; then echo "== $name"; echo "$out"; else printf "%-12s %s\n" "$name" "$(echo "$out" | tail -1)"; fi; return $rc; }
fails=0
run gpio_probe            || fails=$((fails+1))
run edge_probe 2000 500   || fails=$((fails+1))
run spi_probe             || fails=$((fails+1))
run baro_probe            || fails=$((fails+1))
run radio_probe           || fails=$((fails+1))
echo "probes failed: $fails"; exit $fails
REMOTE
)"
