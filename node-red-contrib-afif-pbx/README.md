# node-red-contrib-afif-pbx

Node-RED nodes for the AFIF-PBX panel: incoming SMS, outgoing SMS, call events and PBX
status.

## Install

```bash
cd ~/.node-red
npm install /path/to/node-red-contrib-afif-pbx-1.0.0.tgz
# restart Node-RED
```

## Configuration

Add an **AFIF-PBX** config node:

- **Panel URL** — `http://127.0.0.1:8090` on the Tinker Board.
- **Username** — the panel admin user (default `admin`).
- **Password** — stored as a Node-RED credential, never in the flow JSON.

The nodes log in through `POST /api/login` and reuse the `afif_session` cookie, renewing it
when the 12-hour session expires.

## Nodes

### afif sms in
Polls `GET /api/sms`. Emits each new SMS:

- `msg.payload` — text
- `msg.topic` / `msg.to` — remote number
- `msg.direction`, `msg.status`, `msg.time`, `msg.smsId`
- `msg.sms` — full record

It keeps the highest id it has seen, so a restart does not re-emit old messages. Tick
**Send existing SMS on start** if you want the history once.

### afif sms out
Input: `msg.to` (or `msg.number` / `msg.topic`) and `msg.payload` (text). Posts to
`POST /api/sms` with `{ number, text }`. On success the message continues with
`msg.sent = true`; on failure `msg.error` is set.

### afif call events
Polls `GET /api/calls?limit=100`. Emits new call records with `msg.event` one of
`answered`, `missed`, `busy`, `failed`, `voicemail`, and the full call in `msg.payload`.

### afif status
Polls `GET /api/status`: GSM modem, SIP addresses, registered extensions, active calls,
voicemail count and recent activity. Optionally only emits when the status changes.

## Example flow

`flows/afif-pbx.json` contains a ready tab: SMS in → debug, an inject that sends a test SMS,
call events → debug, and status → debug. Import it as a **new flow** (or use
`scripts/add-flow.mjs` to add only that tab through the Admin API).

## Notes

- The panel must be reachable from Node-RED. On the Tinker Board it is loopback:8090.
- Polling intervals are deliberately conservative (10–30 s) to keep the ARM board idle.
- The PBX API uses a session cookie; there is no API key. Keep Node-RED and the panel on
  the same trusted host, or put both behind the same access layer.
