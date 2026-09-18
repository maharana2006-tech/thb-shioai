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
docker build -t multiship-lan-scanner .

docker run --rm --network host \
  -e MULTISHIP_API_BASE=https://app.multiship.com \
  -e MULTISHIP_AGENT_KEY=xxxxxxxxxxxxxxxx \
  multiship-lan-scanner
```

`--network host` is required so JmDNS can join the mDNS multicast group;
Docker's default bridge network does not forward multicast so no printers
will be discovered without it.

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
