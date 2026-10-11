#!/bin/bash
# Install / control the telemetry beacon service on the Pi.
#   tools/pi_service.sh install     # copy unit + binary, enable, start
#   tools/pi_service.sh status|restart|stop|start|log
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
case "${1:-status}" in
  install)
    "$HERE/tools/pi.sh" scp "$HERE/probes/build/bin/linuxArm64/telemetry_txReleaseExecutable/telemetry_tx.kexe" hab1:/home/hab/telemetry_tx
    "$HERE/tools/pi.sh" scp "$HERE/systemd/hab1-telemetry.service" hab1:/home/hab/hab1-telemetry.service
    "$HERE/tools/pi.sh" 'chmod +x ~/telemetry_tx && sudo install -m 644 ~/hab1-telemetry.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now hab1-telemetry.service && sleep 3 && systemctl --no-pager status hab1-telemetry.service | head -12' ;;
  log)     "$HERE/tools/pi.sh" 'journalctl -u hab1-telemetry.service -n 30 --no-pager' ;;
  *)       "$HERE/tools/pi.sh" "sudo systemctl $1 hab1-telemetry.service; systemctl --no-pager status hab1-telemetry.service | head -12" ;;
esac
