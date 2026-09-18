# Printer scan agent — operator runbook

**Audience:** platform ops + on-call. Covers install, monitor, rotate, revoke, and troubleshoot for the LAN scan agent that customer sites run to auto-detect network printers.

**Change summary:** the printer auto-detect MVP shipped in PRs #687 (backend scaffold), #688 (service + controller), #689 (FE enroll/scan-now panel), #690 (agent Docker image `printer-scan-agent/`), #691 (bulk-add picker), #692 (tenant selector). This runbook covers what happens **after** merge: onboarding a customer, deploying the agent, and reacting when something goes wrong.

Related:

- Backend endpoints: `PrinterScanController.java` — 4 admin routes under `/api/v1/tenants/{tenantCode}/*`, 2 agent routes.
- Design doc: [`docs/printer-auto-detect-design.md`](printer-auto-detect-design.md).
- Agent source: [`printer-scan-agent/`](../printer-scan-agent).

---

## 1. Onboarding a customer

### Preconditions

- [ ] Customer has an ADMIN account on the multiship SaaS (regular `hasRole('ADMIN')` — no separate role).
- [ ] Customer runs Docker somewhere on their warehouse LAN (a Linux VM, a Raspberry Pi, or a spare workstation). **The multiship app server itself cannot scan** — it lives outside the customer's network.
- [ ] Customer has one Docker host per warehouse if they want per-warehouse discovery visibility (each host = one agent enrollment).

### Steps

1. Log in as ADMIN to the multiship SPA. Navigate to `/settings/printers`.
2. In the **Tenant** dropdown at the top of the page, pick the tenant to enroll for.
3. Click **Enroll scanner**. In the modal:
   - **Scanner id** — one-word memorable id, e.g. `warehouse-north`, `dc-atlanta`. Unique per tenant.
   - **Hostname** — optional; the customer's own name for the Docker host (e.g. `scanner01.acme.local`).
4. Click **Enroll**. The modal transitions to step 2 showing the **raw agent key** — this is the only time it appears.
5. Copy the key. Paste it into the customer's `docker run` invocation as `MULTISHIP_AGENT_KEY`:
   ```bash
   docker run --rm --network host \
     -e MULTISHIP_API_BASE=https://app.multiship.com \
     -e MULTISHIP_AGENT_KEY=<paste key here> \
     ghcr.io/multiship/lan-scanner:latest
   ```
6. Confirm the agent shows up as **active** in the panel within one poll interval (~5s). The **last seen** column will populate.

**`--network host` is mandatory.** Docker's default bridge does not forward multicast, and mDNS is multicast-only. Skipping this flag makes the agent look healthy (successful polls) while finding zero printers.

---

## 2. Discovering + registering printers

Once the agent is enrolled and shows **active** on `/settings/printers`:

1. Click **Scan for printers**. The toast confirms how many active agents were nudged. Wait ~10 seconds (agent picks up on next 5s long-poll + 15s scan window).
2. Click **Pick from scan**. The picker drawer opens with every discovered row.
3. Rows already registered (matching `host:port` in the Printers table above) are disabled with an "already registered" badge.
4. Multi-select the printers to add. Use the header checkbox to select all eligible rows.
5. Click **Add N selected**. Success + failure counts are reported in separate toasts.

Guessed fields (`connectionGuess`, `formatGuess`, `paperGuess`) map to defaults. If the guess is wrong, edit the printer via the pencil icon in the Printers table.

---

## 3. Rotating a key

Keys are stored on the backend as SHA-256 hex (not bcrypt — the agent polls every 5s so bcrypt would burn CPU). Once shown at enrollment, the raw key is **unrecoverable**. To rotate:

1. On `/settings/printers`, the active-agents list shows each enrollment. **Revoke** the current row (icon TBD in P4b — for MVP, call `DELETE /api/v1/tenants/{t}/printer-scan-agents/{id}` via the admin token).
2. Enroll a fresh agent with the **same** `agentId`. The backend reuses the row and returns a new key.
3. Update `MULTISHIP_AGENT_KEY` on the customer's Docker host and restart the container. Old key stops working immediately.

Rotate policy: **on every operator change** at the customer site, or **on any suspected leak** (raw key committed to git, screenshot leaked, etc.).

---

## 4. Monitoring

### What "healthy" looks like

- Every active agent has a **last seen** timestamp within ~10 seconds of now.
- New printers added at the customer site show up in the picker within one **Scan for printers** cycle.
- No spike in `printer_scan_agent_key_invalid_total` (once P4b metrics ship).

### What "sick" looks like

| Symptom | Probable cause | Fix |
|---|---|---|
| Enrollment "successful" but agent never contacts backend | Wrong `MULTISHIP_API_BASE` or firewall blocking outbound HTTPS from the Docker host | Curl the base URL from inside the container; check corporate egress rules |
| Agent contacts backend but `last_seen_at` doesn't advance | `MULTISHIP_AGENT_KEY` doesn't match the enrolled hash (leading/trailing whitespace, wrong tenant) | Re-copy key from a fresh enrollment |
| Agent is active but scan finds zero printers on every nudge | Missing `--network host` (single most common) | Restart with the flag |
| Scan finds printers on some warehouses but not others | Warehouse VLAN doesn't route mDNS across subnets | Deploy one agent per subnet |
| Duplicate rows for the same physical printer | Different `host` values (e.g. `192.168.1.42` vs `printer-42.local`) | Delete the DNS-name row; keep the IP row |

### Logs to look at

- **Backend WARN "Printer scan agent enrolled"** — expected on every new enrollment.
- **Backend WARN "Unknown or revoked agent key"** — either a stale key kept running against a revoked row, or a brute-force attempt. Investigate if unexpected.
- **Agent container stderr** — `mDNS scan found N printers` on each nudge; `poll loop error` on any transient network hiccup (auto-retries next tick).

---

## 5. Revoking a scanner (leaving customer, decommission, security incident)

1. On `/settings/printers`, revoke the enrollment row (mechanism as in §3 step 1).
2. Backend flips `active=false` + sets `revoked_at`. The agent's next long-poll returns 401.
3. Ask the customer to stop the Docker container. Their previously discovered printers **remain** in `printer_discovered` (they're history, not credentials) — the picker will just stop showing new rows.
4. If the incident is a leak, also rotate for every **other** agent on the same customer site — the leaked key may be one of a set.

---

## 6. Upgrading the agent image

MVP publishes to `ghcr.io/multiship/lan-scanner:latest`. Customers running `:latest` pick up patches on container restart (or immediately with `docker pull` + restart).

Auto-update is deferred (see [`docs/printer-auto-detect-design.md`](printer-auto-detect-design.md) "open questions" § auto-update). For MVP, notify customers of the recommended tag bump via the operator channel; document breaking changes in the P2 subdir's `README.md` release notes section.

---

## 7. Deploying a new agent version

The `printer-scan-agent-ci.yml` workflow runs `gradle shadowJar` on every PR touching `printer-scan-agent/**`. To cut a release:

1. Bump `version` in `printer-scan-agent/build.gradle.kts`.
2. Tag: `git tag lan-scanner-v0.1.1 && git push --tags`.
3. Build + push the Docker image (registry setup deferred — for MVP, ops builds locally and pushes to `ghcr.io/multiship/lan-scanner:0.1.1` + retag `:latest`).
4. Notify customers to `docker pull` on their host.

---

## 8. Backend-side observability (deferred to P4b)

Not shipped yet — tracked as follow-up:

- `printer_scan_agent_last_seen_seconds{tenant, agentId}` Micrometer gauge exposed via `/actuator/prometheus`.
- Grafana alert: `max(printer_scan_agent_last_seen_seconds) by (tenant,agentId) > 30` for 2m → PagerDuty warning.
- Alert runbook link → this doc, §4 "What sick looks like".

---

## 9. Emergency shutoff

If a scan agent misbehaves in a way that risks the SaaS (e.g. spamming `POST /printers/discovered` with malformed data), revoke the enrollment row and the backend rejects every subsequent request from that key. There is no ops-side kill switch beyond that — the agent runs on customer infra and we cannot reach out to it directly.
