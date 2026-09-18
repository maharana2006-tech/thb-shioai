# Printer auto-detect — research + design (2026-09-18, revised)

Design for a "Scan for printers" affordance on `/settings/printers` that discovers **network / LAN printers** on the tenant's warehouse network. USB printers attached directly to operator PCs stay on the current manual typed-form entry (see rationale in [Non-goals](#non-goals)).

**Revision note**: v1 of this doc recommended an operator-side helper daemon (`multiship-printer-bridge` on operator PCs). Product constraints tightened after v1: (a) no operator-PC install, (b) the backend runs on a Linux server (single central process), (c) deployment is multi-tenant SaaS. This forces a **per-tenant LAN scan agent** on the customer's own infrastructure (their server / NAS / small Docker host inside the warehouse VLAN), NOT on operator machines. Everything downstream is designed around that.

## TL;DR

Ship a small **per-tenant scan agent** (`multiship-lan-scanner`) as a Docker image the customer pulls onto a host inside their warehouse LAN. It runs mDNS (`_ipp._tcp.local.` + `_pdl-datastream._tcp.local.`) + optional SNMP `sysDescr` probes, then POSTs the discovered list to `POST /api/v1/tenants/{tenantCode}/printers/discovered` on a schedule (every 15 min + on-demand push). The central Linux-hosted backend stores + de-dupes the list; the FE's "Scan for printers" button on `/settings/printers` opens a picker drawer of what the agent found. Manual typed-entry stays for USB / air-gapped / one-off printers.

## Current state

The V65 registry (`backend/src/main/resources/db/migration/V65__printer_registry.sql`) already models everything discovery would produce — **zero schema change needed**:

| Field | V65 column | Where discovery gets it |
|---|---|---|
| `connection` | RAW_9100 \| IPP | Port probe (9100 open → RAW_9100, 631 open → IPP) |
| `host` / `port` | inet + int | mDNS TXT / SNMP sysDescr |
| `queue_path` | 160 chars | IPP `rp` TXT record (e.g. `ipp/print`) |
| `format` | ZPL \| PDF | IPP `pdl` TXT record; `application/octet-stream` on 9100 → ZPL heuristic |
| `paper` | LABEL_4X6 \| A4 \| LETTER | IPP `media-default`; ZPL → LABEL_4X6 default |
| `name` / `location` | 120 / 160 chars | mDNS `note` + `ty` TXT records |

- **Backend surface**: `PrinterController.java` (`/api/v1/printers`, CRUD + `POST /{id}/test`), `PrinterService.apply()` (validation choke point), `PrinterTransport.java` (RAW_9100 socket + RFC 8011 IPP). No discovery hook exists.
- **FE surface**: `multiship-react/src/components/PrintersPage.tsx` — the "Add printer" button opens `PrinterEditor` with 8 typed fields. `AppRoutes.tsx:177` mounts at `/settings/printers`. Client: `multiship-react/src/api/printerService.ts`.
- **Deployment reality**: multi-tenant SaaS backend on a Linux server. Backend has ZERO L2 reachability to any tenant's warehouse LAN — mDNS broadcasts do not cross routers / VPNs / NAT.

## Options (revised)

| Option | Finds local (USB) | Finds LAN | Multi-tenant safe | Operator PC install? | Verdict |
|---|---|---|---|---|---|
| **A. Backend-side LAN scanner** (mDNS + SNMP from the Linux server) | No | Only printers reachable from the backend's own LAN | Broken by design for SaaS | No | **Reject** — SaaS backend cannot see any tenant's warehouse LAN |
| **B. Browser `navigator.printers` / Web Print API** | Partial (OS-installed only, Chrome flag only) | No | Yes | No | **Reject** — behind experimental flag; no LAN support; no Firefox / Safari path |
| **C. Operator-side helper daemon** (v1 recommendation) | Yes | Yes | Yes | **Yes** — MSI/DMG/DEB per operator | **Rejected by product** — no operator-PC install allowed |
| **D. Per-tenant scan agent inside customer LAN** (revised recommendation) | No (unless customer PCs share via IPP) | Yes — mDNS + SNMP on the LAN it sits on | Yes — one agent per tenant, self-contained | No | **Recommended** |

## Recommendation: Option D — per-tenant LAN scan agent

### What it looks like

- A small Java 21 service (~15 MB with jlink) shipped as a **Docker image** the customer pulls onto ANY host inside their warehouse LAN (their existing print server, a Synology NAS, a Raspberry Pi, a corner of their vSphere cluster — anywhere it can broadcast on the LAN and reach `api.multiship.app` outbound).
- Runs mDNS (`_ipp._tcp.local.` + `_pdl-datastream._tcp.local.`) + optional SNMP `sysDescr` (`1.3.6.1.2.1.1.1`) walks.
- Authenticates to the central backend with a **tenant-scoped API key** the admin generates in the SPA (`/settings/printers` → "Enable auto-detect" panel → "Generate scanner key").
- Posts discovered list to `POST /api/v1/tenants/{tenantCode}/printers/discovered` on a 15 min schedule + on-demand webhook from the SPA.
- **Zero operator-side install**. Ops-team install only, done once per tenant.

### Auth + wire flow

```
[browser tab @ *.multiship.app]
        │  1) admin clicks "Scan for printers" → POST /api/v1/tenants/{T}/printers/scan-now
        ▼
[backend on Linux server] fans out to the tenant's registered scanner via long-poll queue
        │
        │  2) scan agent (on tenant LAN) picks up the job on its next long-poll
        ▼
[scan agent] runs mDNS + SNMP for ~5s, POSTs result to /printers/discovered
        │
        │  3) backend stores in printer_discovered (new table) + de-dupes by host+port
        │
        │  4) SPA polls /printers/discovered/latest, renders drawer of candidates
        ▼
[operator] multi-selects → backend creates via existing PrinterService.apply()
```

- Long-poll instead of pushed webhook so the agent lives behind NAT with no inbound port opened. Standard "reverse tunnel" pattern (Cloudflare Tunnel / ngrok / GitHub Actions runner all do this).
- Agent's API key is tenant-scoped + purpose-scoped (`purpose=printer-scan`) + revocable from the SPA.

## Proposed implementation phases

**P1 — Backend hooks + FE fallback (1 PR, ~350 LoC)**
- V67 migration: `printer_scan_agent` (tenant_code, api_key_hash, last_seen_at, hostname) + `printer_discovered` (tenant_code, host, port, name, discovered_at, agent_id, raw_txt).
- `POST /api/v1/tenants/{t}/printer-scan-agents` (admin, generates API key + returns once).
- `GET /api/v1/tenants/{t}/printer-scan-agents/poll` (agent long-poll for "scan now" nudges).
- `POST /api/v1/tenants/{t}/printers/discovered` (agent-scoped auth; upsert into `printer_discovered`).
- `POST /api/v1/tenants/{t}/printers/scan-now` (admin; queues the nudge for the agent's next poll).
- `GET /api/v1/tenants/{t}/printers/discovered/latest` (admin; returns most-recent scan).
- FE: "Enable auto-detect" panel on `/settings/printers` — generates + shows the key once, plus a "Scan for printers" button in the header (disabled + tooltip when no agent has ever posted).

**P2 — Scan agent MVP (separate repo `multiship-lan-scanner`, first Docker tag)**
- Java 21 single-jar in a `~50MB` scratch/distroless Docker image.
- `PrintServiceLookup.lookupPrintServices()` for host-local (in case the agent host itself is on a print server) + JmDNS for LAN.
- Config via env vars: `MULTISHIP_API_BASE`, `MULTISHIP_TENANT_CODE`, `MULTISHIP_AGENT_KEY`.
- Docker Hub image `multiship/lan-scanner:latest`; ops runs `docker run -d --network host multiship/lan-scanner:latest` (host network for mDNS broadcast reachability).

**P3 — FE picker UX (1 PR, ~250 LoC)**
- "Scan for printers" button triggers scan-now + polls `/discovered/latest` every 3s for 60s.
- Discovered drawer: multi-select rows, per-row pre-filled connection / format / paper / name guesses, one-click "Add 3 printers" → bulk POST to existing `POST /api/v1/printers`.
- Inline "Assign to Default LABEL / COMMERCIAL_INVOICE" checkboxes per row.

**P4 — Ops runbook + auto-update (post-MVP, deferred)**
- `docker-compose.yml` template for customer ops teams.
- Watchtower-based auto-update or documented `docker pull + restart` cadence.
- Grafana dashboard hint for `printer_scan_agent.last_seen_at` (alert if > 60 min).

## Non-goals

- **USB-attached-to-operator-PC printers**. Backend can't reach them; browser can't enumerate them reliably; agent lives in the LAN not on the PC. These stay on manual typed entry. In practice, warehouse label printers (Zebra ZD421 / ZT411 / industrial Zebras) are network-native anyway; USB is a corner case.
- **Print-through-scan-agent**. The agent only discovers; the backend keeps owning the print job via existing `PrinterTransport`. Printing through the agent would need a much bigger auth model — separate feature if ever needed.
- **Driver installation**. We rely on the printer being reachable at its documented protocol; we do not push drivers.
- **SNMP full-tree walk**. We probe `sysDescr` only. mDNS gives us all the fields we need for 90%+ of network label printers and is 100× faster.
- **Deprecating manual entry**. Air-gapped printers, unusual IPP paths, admin-only IPP-over-HTTPS setups still need the typed form. UI copy will make it clear that scan is a convenience, not a requirement.
- **Operator-side helper daemon** (v1 recommendation). Product killed the "operator PC install" path.

## Open questions (need product/ops decision before P2)

1. **Docker image distribution channel**. Docker Hub public image (easy but any random pull works) vs. gated GHCR / private registry (auth burden, controlled rollout). Recommend: public Docker Hub with `:latest` + immutable version tags; customer's agent key is the security boundary, not image access.
2. **Agent auto-update policy**. Watchtower auto-pull (agent picks up new versions within 5 min of push) vs. pinned `:1.2.3` tags customers upgrade manually. Recommend: pinned by default + a `MULTISHIP_AUTO_UPDATE=true` env for customers who want it. Auto-update on a service that holds a tenant API key is a real supply-chain risk.
3. **Multi-agent per tenant**. Can a customer with 3 warehouses run 3 agents (one per site)? Recommend yes; keep the schema keyed by `agent_id` so `printer_discovered` shows which warehouse each printer came from. UI just adds a "warehouse" label per discovered row.

---

**Doc**: `docs/printer-auto-detect-design.md`
