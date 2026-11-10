#!/usr/bin/env bash
#
# Install or upgrade AFIF-PBX on a Linux ARM board (Tinker Board / Tinker Board 2).
# Run as root:  sudo bash install.sh
#
#   VERSION=latest            release tag (default latest)
#   AFIF_PBX_REPO=owner/repo  override the GitHub repository
#
set -euo pipefail

REPO="${AFIF_PBX_REPO:-afifkhoury2019/AFIF-PBX-NodeRED}"
VERSION="${VERSION:-latest}"
DATA_DIR=/var/lib/afif-pbx
APP_DIR=/opt/afif-pbx
RUN_USER=afif-pbx

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root: sudo bash $0"

case "$(uname -m)" in
  aarch64|arm64) ARCH=arm64 ;;
  armv7l|armhf)  ARCH=armhf ;;
  *) die "Unsupported architecture: $(uname -m). This build supports armhf and arm64." ;;
esac
say "Architecture: $(uname -m) ($ARCH)"

command -v curl >/dev/null || die "curl is required"
command -v tar >/dev/null || die "tar is required"

# Private repositories need a token, or the tarball can sit next to this script.
AUTH=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
  AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

ASSET="afif-pbx-linux-$ARCH.tar.gz"
LOCAL_TAR="${AFIF_PBX_TARBALL:-$(dirname "$0")/$ASSET}"
TMP="$(mktemp -d)"
URL=""

if [ -f "$LOCAL_TAR" ]; then
  say "Using local $LOCAL_TAR"
else
  if [ "$VERSION" = "latest" ]; then
    API="https://api.github.com/repos/$REPO/releases/latest"
  else
    API="https://api.github.com/repos/$REPO/releases/tags/$VERSION"
  fi
  say "Resolving release: $VERSION"
  JSON="$(curl "${AUTH[@]}" -fsSL "$API")" || die "Could not read $API. Download $ASSET from the release page and put it next to install.sh, or set GITHUB_TOKEN."
  URL="$(printf '%s' "$JSON" | grep -o '"browser_download_url"[^"]*"[^"]*"' | sed -E 's/.*"(https:[^"]+)"/\1/' | grep "/$ASSET$" | head -n1 || true)"
  [ -n "$URL" ] || die "Release has no $ASSET asset. Download it from the release page."
fi

trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- dependencies
say "Installing runtime dependencies"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates tar espeak-ng usb-modeswitch usb-modeswitch-data sqlite3 >/dev/null || true
for pkg in libicu72 libicu70 libicu67 libicu66 libssl3 libssl1.1 zlib1g libsqlite3-0; do
  apt-get install -y -qq "$pkg" >/dev/null 2>&1 || true
done

if apt-cache show piper >/dev/null 2>&1; then
  apt-get install -y -qq piper >/dev/null || true
fi

# ---------------------------------------------------------------- service user
if ! id -u "$RUN_USER" >/dev/null 2>&1; then
  say "Creating the $RUN_USER user"
  adduser --system --group --home "$DATA_DIR" --no-create-home --disabled-login "$RUN_USER"
fi
for g in dialout audio; do
  getent group "$g" >/dev/null && usermod -aG "$g" "$RUN_USER"
done

mkdir -p "$APP_DIR" "$DATA_DIR" /var/log/afif-pbx
chown -R "$RUN_USER:$RUN_USER" "$DATA_DIR" /var/log/afif-pbx

# ---------------------------------------------------------------- download
mkdir -p "$TMP/app"
if [ -n "$URL" ]; then
  say "Downloading $ASSET"
  curl "${AUTH[@]}" -fL "$URL" -o "$TMP/afif-pbx.tar.gz"
else
  say "Copying $LOCAL_TAR"
  cp "$LOCAL_TAR" "$TMP/afif-pbx.tar.gz"
fi
tar -xzf "$TMP/afif-pbx.tar.gz" -C "$TMP/app"
[ -x "$TMP/app/AFIF-PBX-Service" ] || die "The archive does not contain AFIF-PBX-Service"

say "Stopping the old service (if any)"
systemctl stop afif-pbx 2>/dev/null || true

rm -rf "$APP_DIR.new"
mkdir -p "$APP_DIR.new"
cp -a "$TMP/app/." "$APP_DIR.new/"
chown -R root:root "$APP_DIR.new"
rm -rf "$APP_DIR.old"
[ -d "$APP_DIR" ] && mv "$APP_DIR" "$APP_DIR.old"
mv "$APP_DIR.new" "$APP_DIR"
rm -rf "$APP_DIR.old"

# ---------------------------------------------------------------- systemd + udev
say "Installing service and udev rules"
cat >/etc/systemd/system/afif-pbx.service <<'EOS'
[Unit]
Description=AFIF-PBX phone system
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=afif-pbx
Group=afif-pbx
SupplementaryGroups=dialout audio
WorkingDirectory=/opt/afif-pbx
Environment=AFIF_PBX_DATA=/var/lib/afif-pbx
ExecStart=/opt/afif-pbx/AFIF-PBX-Service --data /var/lib/afif-pbx
Restart=on-failure
RestartSec=3
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
EOS

cat >/etc/udev/rules.d/99-afif-pbx.rules <<'EOS'
# Huawei E303: switch the virtual CD-ROM (12d1:1f01) to modem mode.
ACTION=="add", SUBSYSTEM=="usb", ATTR{idVendor}=="12d1", ATTR{idProduct}=="1f01", RUN+="/usr/sbin/usb_modeswitch -v 12d1 -p 1f01 -M 55534243123456780000000000000011060000000000000000000000000000"

# Stable names for the two E303 serial interfaces.
# ttyUSB0 = AT / PC UI; ttyUSB1 = DIAG / voice (PCM after AT^DDSETEX=2).
ACTION=="add", SUBSYSTEM=="tty", KERNEL=="ttyUSB2", ATTRS{idVendor}=="12d1", SYMLINK+="afif-gsm-at", GROUP="dialout", MODE="0660"
ACTION=="add", SUBSYSTEM=="tty", KERNEL=="ttyUSB1", ATTRS{idVendor}=="12d1", SYMLINK+="afif-gsm-voice", GROUP="dialout", MODE="0660"

# Make sure the option driver binds the modem ports after a mode switch.
ACTION=="add", SUBSYSTEM=="usb", ATTR{idVendor}=="12d1", RUN+="/sbin/modprobe option"
EOS

systemctl daemon-reload
udevadm control --reload-rules 2>/dev/null || true
udevadm trigger 2>/dev/null || true

systemctl enable afif-pbx >/dev/null
systemctl restart afif-pbx

# ---------------------------------------------------------------- wait for panel
say "Waiting for the web panel"
PORT=""
for _ in $(seq 1 60); do
  if [ -s "$DATA_DIR/http-port.txt" ]; then
    PORT="$(cat "$DATA_DIR/http-port.txt")"
    break
  fi
  sleep 1
done
[ -n "$PORT" ] || PORT=8090

IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$IP" ] || IP="<board-ip>"

cat <<EOF

AFIF-PBX is installed.

  Panel:   http://$IP:$PORT
  Login:   admin / admin   (you will be asked to change it)
  Data:    $DATA_DIR
  Service: systemctl status afif-pbx
  Logs:    journalctl -u afif-pbx -f

The GSM dongle should be in modem mode. If it is still a CD-ROM, unplug and
re-plug it once after the udev rules are installed.

Node-RED integration:
  cd ~/.node-red && npm install /path/to/node-red-contrib-afif-pbx-1.0.0.tgz
  import flows/afif-pbx.json as a new flow (or run scripts/add-flow.mjs)

EOF
