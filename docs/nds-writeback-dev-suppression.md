# Suppressing NDS writeback in dev / test deployments

**Contract** (per `ShipX_NDS_Orders_and_Tracking.docx` §1):
> "Tracking numbers are only sent back to NDS from the live ShipX system.
> The test (development) system still creates labels, but it never writes
> anything to NDS."

## How to enforce it (no code changes needed)

The V91 external-system connection model lets ops kill writeback per
environment without touching the codebase. Two workable patterns:

### Pattern A — no DEV row (recommended for dev / staging)

Do not create a DEV row for `nds-default` at all. Set the PROD row's
`active = false` in dev/staging environments (via
`UPDATE external_system_connection SET active = FALSE WHERE name = 'nds-default';`).

The dispatcher's `active` check short-circuits before any writeback fires.
Every source / channel gate is bypassed. Result: dev / staging generate
labels normally but never touch NDS.

### Pattern B — inactive DEV row (matches "kill switch" semantics)

If you want the PROD row to stay live (e.g. for read-only NDS prefill
lookups on `.X` / `.Y` scans) but block all writes:

1. Create a DEV row for `nds-default` with `active = FALSE`.
2. Set the PROD row's `use_dev = TRUE`.
3. Resolver hands the DEV row back; dispatcher sees `active = FALSE` and
   skips. Read paths through `NdsTemplates.production()` still hit the
   PROD row via `registry.connect()`, so prefill / debug endpoints keep
   working.

## Verification

After flipping, fire a writeback probe:

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/external-systems/1/writeback-probe \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" -b cookies.txt \
  -d '{"source":"MANUAL","channel":"D2C","clientCode":"THB000","orderNo":99900001}'
```

Expected log line:
```
writeback: skipping generate for order=99900001 client=THB000 — no connection wired
```
(or `skipping generate — connection 'nds-default' inactive` when the row exists but is inactive).

## Why no code-side profile guard

Considered gating on `spring.profiles.active` inside the dispatcher, but
that reads a static value at boot and can't be adjusted at runtime for
per-tenant escape hatches. The V91 admin toggle is DB-driven, hot
reloadable, and already covers the case. Adding a profile guard would
duplicate the enforcement without adding real safety.
