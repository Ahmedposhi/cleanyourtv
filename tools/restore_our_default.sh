#!/usr/bin/env bash
# Restores the verified default theme (Sweden layout + 14 rotating landscapes @ 30m) to the TV
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"
: "${TV_HOST:=192.168.68.112}"
export TV_HOST
echo "Pushing default theme to TV ($TV_HOST)..."
tools/theme.sh themes/default
echo "Default theme restored successfully!"
