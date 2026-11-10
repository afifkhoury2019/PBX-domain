# Migrating from Windows AFIF-PBX

The Linux port uses the same `config.json` and SQLite schema. Migration has two parts:
the **configuration** (the panel's Backup file) and the **data directory** (database,
prompts, recordings, voicemail).

## 1. On Windows

- Panel → **Settings → Backup** downloads `afif-pbx-config-YYYYMMDD.json`.
- Copy the data directory too: `%ProgramData%\AFIF-PBX` (the path is printed at the
  top of the panel log). It contains `afif-pbx.db`, `Prompts/`, `Recordings/`,
  `Voicemail/` and the logs.

## 2. Install on the board

Follow `docs/linux.md`, then open `http://<board>:8090`.

## 3. Restore the configuration

Panel → **Settings → Restore** → choose the JSON backup. Or from a shell:

```bash
sudo bash deploy/migrate.sh /path/to/afif-pbx-config-YYYYMMDD.json http://127.0.0.1:8090
```

The restore endpoint takes the JSON body directly (not a multipart upload). The
current admin password is kept; extensions, ring groups, IVRs, settings and the
call-routing configuration are replaced.

## 4. Copy the data

Stop the service, copy the SQLite database and media, fix ownership, start:

```bash
sudo systemctl stop afif-pbx

sudo cp /path/to/windows/AFIF-PBX/afif-pbx.db /var/lib/afif-pbx/
sudo cp -a /path/to/windows/AFIF-PBX/Prompts/. /var/lib/afif-pbx/Prompts/
sudo cp -a /path/to/windows/AFIF-PBX/Recordings/. /var/lib/afif-pbx/Recordings/
sudo cp -a /path/to/windows/AFIF-PBX/Voicemail/. /var/lib/afif-pbx/Voicemail/

sudo chown -R afif-pbx:afif-pbx /var/lib/afif-pbx
sudo systemctl start afif-pbx
```

If you only restore the JSON, calls/SMS history and existing recordings are not
carried over; copy the database and media for those.

## 5. Re-enter secrets

Windows DPAPI values cannot be decrypted on Linux. Re-enter:

- Cloudflare API token (DNS feature),
- Tailscale auth key (secure network),
- Telegram bot token and chat id,
- any provider password stored as a secret.

Extension secrets, ring groups, IVR options and the admin password hash come from
the restored JSON and keep working.

## 6. Verify

- Extensions register on SIP 5060/5160.
- Ring groups, IVR `*43`, voicemail.
- GSM line, SMS in/out including Arabic UCS2.
- Recordings and voicemail audio in the panel.
- Node-RED **AFIF-PBX** tab receives status/SMS.
