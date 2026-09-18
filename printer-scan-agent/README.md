# multiship-lan-scanner

Per-tenant printer discovery agent for the multiship SaaS.

Runs on customer infrastructure (Docker, `--network host`), long-polls the
multiship API for scan-now nudges from the admin, and posts discovered
printers back for the admin to promote via the picker in
`/settings/printers`.

Backend counterpart: `PrinterScanController` + `PrinterScanService` in the
main `thb-shioai` repo. See `docs/printer-auto-detect-design.md` there for
the full multi-tenant SaaS reasoning + auth model.

## Env vars

| Var | Required | Default | Purpose |
|-----|----------|---------|---------|
| `MULTISHIP_API_BASE` | yes | — | e.g. `https://app.multiship.com` |
| `MULTISHIP_AGENT_KEY` | yes | — | raw key shown ONCE at enrollment |
| `MULTISHIP_POLL_SECS` | no | 5 | long-poll interval |
| `MULTISHIP_SCAN_TIMEOUT_SECS` | no | 15 | mDNS listen window per service type |
| `MULTISHIP_UPDATE_CHECK_MINUTES` | no | 60 | auto-update poll interval (P4c) |

## Enrollment

The admin enrolls this scanner via the API before launching the container:

```
POST /api/v1/tenants/{tenantCode}/printer-scan-agents
{ "agentId": "warehouse-north", "hostname": "scanner01.corp" }
→ { "agentRowId": 42, "rawKey": "<64-hex-chars>" }
```

Copy the `rawKey` into `MULTISHIP_AGENT_KEY`. The key is shown only once; if
it's lost, revoke the agent row via `DELETE /printer-scan-agents/{id}` and
re-enroll for a fresh key.

## Run (Docker)

```bash
docker pull ghcr.io/maharana2006-tech/multiship-lan-scanner:latest

docker run -d --restart=always --network host \
  --name multiship-lan-scanner \
  -e MULTISHIP_API_BASE=https://app.multiship.com \
  -e MULTISHIP_AGENT_KEY=xxxxxxxxxxxxxxxx \
  ghcr.io/maharana2006-tech/multiship-lan-scanner:latest
```

`--network host` is required so JmDNS can join the mDNS multicast group;
Docker's default bridge network does not forward multicast so no printers
will be discovered without it.

### Auto-update

The agent polls `/api/v1/printer-scan-agents/latest-version` every hour
(configurable via `MULTISHIP_UPDATE_CHECK_MINUTES`). On version mismatch
it exits — Docker's `--restart=always` restarts the same-tag image, but
does NOT pull a new one. Pair one of these with the agent to actually
pick up new versions:

- **Watchtower** (simplest, single-line): `docker run -d --name watchtower -v /var/run/docker.sock:/var/run/docker.sock containrrr/watchtower --interval 300 multiship-lan-scanner`
- **Cron**: nightly `docker pull … && docker restart multiship-lan-scanner`
- **systemd** with `ExecStartPre=/usr/bin/docker pull …`

Without one of those, the agent will restart itself hourly but keep running
the same version indefinitely.

## Local dev

```bash
./gradlew shadowJar
MULTISHIP_API_BASE=http://localhost:8080 \
MULTISHIP_AGENT_KEY=... \
java -jar build/libs/multiship-lan-scanner-0.1.0.jar
```

## What it scans

- `_ipp._tcp.local.` / `_ipps._tcp.local.` — IPP printers (guess `IPP`)
- `_pdl-datastream._tcp.local.` — raw port 9100 printers (guess `RAW_9100`)
- `_printer._tcp.local.` — LPD (515)

TXT records used: `rp` → `queuePath`, `note` → `location`, `pdl` →
`formatGuess` (`ZPL` / `PDF`). Every other TXT is dumped into `rawTxt` so
the admin can see what the printer advertised.

## Related

- Backend: `PrinterScanController.java`, `PrinterScanService.java`
- FE: `PrinterScanPanel.tsx`
- Design: `docs/printer-auto-detect-design.md`
