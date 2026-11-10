#!/usr/bin/env bash
#
# Restore a Windows AFIF-PBX configuration backup on the Linux panel.
#   sudo bash migrate.sh /path/to/afif-pbx-config-YYYYMMDD.json [http://127.0.0.1:8090]
#
# This restores config.json only. Copy afif-pbx.db, Prompts, Recordings and
# Voicemail separately (see docs/migration.md).
#
set -euo pipefail

BACKUP="${1:-}"
URL="${2:-http://127.0.0.1:8090}"
URL="${URL%/}"

[ -n "$BACKUP" ] && [ -f "$BACKUP" ] || { echo "usage: $0 <config.json> [panel-url]"; exit 1; }
command -v curl >/dev/null || { echo "curl is required"; exit 1; }

read -r -p "Admin user [admin]: " USER
USER="${USER:-admin}"
read -r -s -p "Admin password: " PASS
echo

JAR="$(mktemp)"
trap 'rm -f "$JAR" /tmp/afif-login.json /tmp/afif-restore.json' EXIT

CODE="$(curl -sS -o /tmp/afif-login.json -w '%{http_code}' -c "$JAR" \
  -H 'content-type: application/json' \
  -d "{\"user\":\"$USER\",\"password\":\"$PASS\"}" \
  "$URL/api/login")"
if [ "$CODE" != "200" ]; then
  echo "Login failed ($CODE):"; cat /tmp/afif-login.json; echo; exit 1
fi

echo "Restoring $(basename "$BACKUP")…"
CODE="$(curl -sS -o /tmp/afif-restore.json -w '%{http_code}' -b "$JAR" \
  -H 'content-type: application/json' \
  --data-binary "@$BACKUP" \
  "$URL/api/restore")"
if [ "$CODE" != "200" ]; then
  echo "Restore failed ($CODE):"; cat /tmp/afif-restore.json; echo; exit 1
fi

echo "Configuration restored."
echo "Copy afif-pbx.db, Prompts, Recordings and Voicemail into /var/lib/afif-pbx,"
echo "then re-enter DPAPI secrets (Cloudflare token, Tailscale auth key, Telegram token)."
