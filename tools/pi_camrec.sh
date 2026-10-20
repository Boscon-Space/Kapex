#!/bin/bash
# Install / control camrec on the Pi.
#   tools/pi_camrec.sh install | status | start | stop | restart | log | check | pull | record [seconds]
#   record: one clip of N seconds (default 60) into ~/footage, with the service stopped; prints the segment line
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
BIN="$HERE/camrec/build/bin/linuxArm64/camrecReleaseExecutable/camrec.kexe"
case "${1:-status}" in
  install)
    "$HERE/tools/pi.sh" scp "$BIN" hab1:/home/hab/camrec.new
    "$HERE/tools/pi.sh" scp "$HERE/systemd/hab1-camrec.service" hab1:/home/hab/hab1-camrec.service
    "$HERE/tools/pi.sh" 'chmod +x ~/camrec.new && mv ~/camrec.new ~/camrec && mkdir -p ~/footage && sudo install -m 644 ~/hab1-camrec.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable hab1-camrec.service >/dev/null 2>&1; sudo systemctl restart hab1-camrec.service && sleep 4 && systemctl --no-pager status hab1-camrec.service | head -6; sha256sum ~/camrec | cut -c1-16'
    shasum -a 256 "$BIN" | cut -c1-16 ;;
  record)
    N="${2:-60}"
    "$HERE/tools/pi.sh" "sudo systemctl stop hab1-camrec.service; ~/camrec /dev/v4l/by-id/usb-046d_C270_HD_WEBCAM_2BA96170-video-index0 /home/hab/footage $N 1280x720 15 500 1 | tee -a ~/footage/record.log | grep -E 'segment|camera_absent|camera_lost|paused'" ;;
  log)   "$HERE/tools/pi.sh" 'journalctl -u hab1-camrec.service -n 30 --no-pager -o cat' ;;
  check)
    # last finished segment (frames, drops, index, RIFF size) and the open file as a brownout would leave it; see tools/avi_segment_check.py
    "$HERE/tools/pi.sh" 'python3 - finished' < "$HERE/tools/avi_segment_check.py"
    "$HERE/tools/pi.sh" 'python3 - open' < "$HERE/tools/avi_segment_check.py" ;;
  pull)
    # newest finished segment to the Mac, then AVFoundation decodes it (independent of our writer)
    OUT="${2:-$HERE/../footage}"; mkdir -p "$OUT"   # BosconSpace/footage on the Mac (git-ignored)
    F=$("$HERE/tools/pi.sh" 'f=$(ls -t ~/footage/*.avi | head -1); grep -q idx1 "$f" && echo "$f" || ls -t ~/footage/*.avi | sed -n 2p')   # newest finished file (an open one has no idx1 yet)
    "$HERE/tools/pi.sh" scp "hab1:$F" "$OUT/"
    swift "$HERE/tools/avi_check.swift" "$OUT/$(basename "$F")" ;;
  *)     "$HERE/tools/pi.sh" "sudo systemctl $1 hab1-camrec.service; systemctl --no-pager status hab1-camrec.service | head -8" ;;
esac
