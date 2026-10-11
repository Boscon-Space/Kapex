#!/bin/bash
# Install / control flightd on the Pi. Replaces the telemetry-only beacon service.
#   tools/pi_flightd.sh install | status | restart | stop | start | log | prompt "text"
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
case "${1:-status}" in
  install)
    # copy beside the running binary and rename: overwriting a running executable fails with "text file busy"
    "$HERE/tools/pi.sh" scp "$HERE/flightd/build/bin/linuxArm64/flightdReleaseExecutable/flightd.kexe" hab1:/home/hab/flightd.new
    "$HERE/tools/pi.sh" scp "$HERE/systemd/hab1-flightd.service" hab1:/home/hab/hab1-flightd.service
    "$HERE/tools/pi.sh" 'chmod +x ~/flightd.new && mv ~/flightd.new ~/flightd && sudo install -m 644 ~/hab1-flightd.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl disable --now hab1-telemetry.service 2>/dev/null; sudo systemctl enable hab1-flightd.service >/dev/null 2>&1; sudo systemctl restart hab1-flightd.service && sleep 8 && systemctl --no-pager status hab1-flightd.service | head -8; sha256sum ~/flightd | cut -c1-16'
    shasum -a 256 "$HERE/flightd/build/bin/linuxArm64/flightdReleaseExecutable/flightd.kexe" | cut -c1-16 ;;
  log)     "$HERE/tools/pi.sh" 'journalctl -u hab1-flightd.service -n 40 --no-pager -o cat' ;;
  prompt)  shift; "$HERE/tools/pi.sh" "mkdir -p ~/prompts; n=q\$(date +%s); printf '%s' \"$*\" > ~/prompts/\$n.txt; echo queued \$n; for i in \$(seq 1 40); do sleep 1; [ -f /log/answers/\$n.answer ] && { echo '--- answer:'; cat /log/answers/\$n.answer; echo; exit 0; }; done; echo 'no answer within 40 s'" ;;
  *)       "$HERE/tools/pi.sh" "sudo systemctl $1 hab1-flightd.service; systemctl --no-pager status hab1-flightd.service | head -14" ;;
esac
