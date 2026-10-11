#!/bin/bash
# ssh/scp wrapper for the payload Pi over the USB gadget link.
#   tools/pi.sh                 # interactive shell
#   tools/pi.sh <command...>    # run a command
#   tools/pi.sh scp <src> <dst> # copy; use hab1: prefix for the Pi side
PI_HOST=${PI_HOST:-hab1.local}   # Wi-Fi name; 10.12.194.1 if ever on the USB gadget link
OPTS=(-i "$HOME/.ssh/hab1_pi" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8 -o UserKnownHostsFile="$HOME/.ssh/known_hosts_hab1")
if [ "${1:-}" = scp ]; then shift; exec scp "${OPTS[@]}" "${@//hab1:/hab@$PI_HOST:}"; fi
exec ssh "${OPTS[@]}" "hab@$PI_HOST" "$@"
