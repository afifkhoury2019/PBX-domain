# AFIF-PBX on Linux ARM (Tinker Board)

This branch ports the Windows AFIF-PBX service to Linux on the ASUS Tinker Board
(`armv7l`) and Tinker Board 2/2S (`aarch64`). The SIP/RTP behaviour, extension
secrets and SMS delivery to AFIF Phone are unchanged; the Windows-only platform
pieces are replaced.

## Build

GitHub Actions builds both targets:

- `linux-arm` (GOARM=7) → `afif-pbx-linux-armhf.tar.gz`
- `linux-arm64` → `afif-pbx-linux-arm64.tar.gz`

Each archive contains the self-contained `AFIF-PBX-Service`, the Node-RED
`afif-net` userspace engine, and `wwwroot`.

## What changed

| Windows | Linux |
| --- | --- |
| Windows service host (`UseWindowsService`) | `UseSystemd()` on Linux; systemd unit in `deploy/` |
| WMI COM-port discovery | sysfs USB interface scan in `Gsm/LinuxPortDiscovery.cs`; interface 02 = AT, 01 = voice; AT probe; `/dev/afif-gsm-at` / `/dev/afif-gsm-voice` from udev |
| WMI/mode-switch driver | `usb_modeswitch` udev rule for `12d1:1f01`; `option` driver binding |
| WinUSB | removed on Linux (the Windows code stays compiled only on Windows paths) |
| Windows SAPI | Piper when a voice model exists, `espeak-ng` fallback; WAV is converted to 8 kHz mono by `Wav` |
| DPAPI | AES-GCM with `/var/lib/afif-pbx/.key` (0600); `dpapi:` and `plain:` prefixes still recognised |
| netsh firewall | no-op on Linux; document nftables/ufw rules instead |
| `%ProgramData%\AFIF-PBX` | `/var/lib/afif-pbx` (or `AFIF_PBX_DATA`) |
| panel port 80 | panel port **8090** |

## Install

The repository is private, so download both files from the release page
(`linux-v0.1.0`) while signed in:

- `install.sh`
- `afif-pbx-linux-armhf.tar.gz` (Tinker Board / S) or
  `afif-pbx-linux-arm64.tar.gz` (Tinker Board 2 / 2S)

Put them in the same directory on the board and run:

```bash
sudo bash install.sh
```

The installer uses the local tarball when it sits next to `install.sh`; otherwise it
resolves the release asset (set `GITHUB_TOKEN` for a private repository).

The installer detects `armv7l`/`aarch64`, downloads the matching release asset,
installs `usb-modeswitch`, `espeak-ng`, ICU/OpenSSL/SQLite runtime libraries,
creates the `afif-pbx` user in `dialout` and `audio`, installs the systemd unit
and udev rules, and starts the service. Re-running it upgrades the binary and
keeps `/var/lib/afif-pbx`.

Panel: `http://<board>:8090` — first login `admin` / `admin` (change it).

## GSM notes

The E303 exposes two serial interfaces. The installer ships:

```
/dev/afif-gsm-at      -> interface 02 (AT / PC UI)
/dev/afif-gsm-voice   -> interface 01 (voice, after AT^DDSETEX=2)
```

`LinuxPortDiscovery` first prefers those stable names, then reads
`/sys/class/tty/ttyUSB*/device/../bInterfaceNumber` and probes `AT` on each
candidate before opening the pair. If the dongle is still a virtual CD-ROM,
unplug/re-plug once after the udev rules are installed. `AT^DDSETEX=2` is sent by
`HuaweiModem` when the voice channel is opened; check `journalctl -u afif-pbx`
for the modem state.

## Piper

Piper is optional. With a voice model in `/usr/share/piper/voices` the prompts
use Piper; otherwise `espeak-ng` is used. Arabic text prefers
`ar_JO-kareem-*` when present. The output is cached in
`/var/lib/afif-pbx/Cache/tts` and converted to 8 kHz mono by `Wav`.

## Node-RED (add-only)

`node-red-contrib-afif-pbx` provides the panel config, SMS in/out, call events
and status. `flows/afif-pbx.json` is a new tab only; `scripts/add-flow.mjs`
posts it through the Admin API `POST /flow`. It never replaces `flows.json`.

## What still needs hardware

- The real E303 on the board (interface numbers, `AT^DDSETEX=2`, 20 ms media clock).
- A real SIM (SMS, Arabic UCS2, multipart, receipts).
- The Tailscale sign-in (`afif-net` is built, but a real tailnet login is interactive).
- Piper Arabic voice quality and the exact `ar_JO` model availability.

## Cloudflare

`pbx.powerchain.uk → http://localhost:8090` must be added to the existing tunnel
by the owner. This branch does not change any Cloudflare settings. SIP/RTP must
not go through Cloudflare; remote phones keep using the built-in secure network.
