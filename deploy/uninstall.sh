#!/usr/bin/env bash
# Remove AFIF-PBX from a Linux ARM board. Data is kept unless --purge is given.
set -euo pipefail

PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

[ "$(id -u)" -eq 0 ] || { echo "Run as root"; exit 1; }

systemctl disable --now afif-pbx 2>/dev/null || true
rm -f /etc/systemd/system/afif-pbx.service
rm -f /etc/udev/rules.d/99-afif-pbx.rules
systemctl daemon-reload
udevadm control --reload-rules 2>/dev/null || true

rm -rf /opt/afif-pbx

if [ "$PURGE" -eq 1 ]; then
  rm -rf /var/lib/afif-pbx /var/log/afif-pbx
  id -u afif-pbx >/dev/null 2>&1 && deluser --system afif-pbx 2>/dev/null || true
  echo "AFIF-PBX and its data were removed."
else
  echo "AFIF-PBX removed. Data kept in /var/lib/afif-pbx (use --purge to delete)."
fi
