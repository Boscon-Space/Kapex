#!/bin/bash
# Build HAB-1 Station as a macOS app and install it into /Applications.
# Packaging needs a non-Homebrew JDK (Compose refuses Homebrew's): set
# STATION_JDK to a Corretto/Adoptium JDK home, or the default below.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
export STATION_JDK=${STATION_JDK:-/Users/adityashinde/Library/Java/JavaVirtualMachines/corretto-22.0.2/Contents/Home}
[ -x "$STATION_JDK/bin/jpackage" ] || { echo "no jpackage at $STATION_JDK; set STATION_JDK"; exit 1; }
cd "$HERE"
gradle --no-daemon -q :station:createDistributable
APP=$(ls -d station/build/compose/binaries/main/app/*.app | head -1)
DEST="/Applications/$(basename "$APP")"
pkill -f "space.boscon.kapex.station.MainKt" 2>/dev/null || true
rm -rf "$DEST"; cp -R "$APP" /Applications/
echo "installed $DEST ($(du -sh "$DEST" | cut -f1)); settings in ~/.hab1-station.properties"
open -a "$DEST"
