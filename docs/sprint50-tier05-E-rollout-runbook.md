# Sprint 50 Tier 0.5 PR E — Rollout runbook

**Audience:** ops + on-call. This runbook is for the operational rollout of the tenant-scope tightening shipped in PR #118. The PR ships the mechanism; this doc drives the behavior change.

**Change summary:** with the feature flag `access.scope-user-by-client=true`, a `USER` row with a non-null `client_code` becomes tenant-scoped — that user can only read/write rows for their own client. Without the flag, USER behaves as it always has (org-wide operator). Flag defaults `false` at merge; do not flip until this runbook is complete.

---

## Prerequisites

- [ ] PR #118 merged to `dev` and deployed to staging.
- [ ] Flyway V5 successfully applied on staging (adds `users.deactivated_at`, `deactivated_by`, and the `user_admin_audit` table).
- [ ] Admin able to reach `/settings/users` in staging.
- [ ] `ACCESS_SCOPE_USER_BY_CLIENT` env var **unset or `false`** on staging + prod.

---

## Stage 1 — Audit legacy USERs (staging + prod)

Run this against each environment's DB. Every row returned is a USER that will lose access when the flag flips unless assigned a `client_code`:

```sql
SELECT id, username, email, full_name, created_at
FROM users
WHERE role = 'USER'
  AND client_code IS NULL
  AND deactivated_at IS NULL
ORDER BY created_at;
```

Sanity checks:

```sql
-- Should be 0. Any row means an ADMIN was assigned a clientCode by
-- mistake; ADMIN is always org-wide.
SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND client_code IS NOT NULL;

-- Should equal the count from the primary query above.
SELECT COUNT(*) FROM users WHERE role = 'USER' AND client_code IS NULL AND deactivated_at IS NULL;

-- TENANT rows should all have client_code populated (PR A backfilled from
-- uppercase(username)). Any null here is a bug — investigate before flip.
SELECT id, username FROM users WHERE role = 'TENANT' AND client_code IS NULL;
```

Record the legacy USER count in the rollout tracker. This is the backfill workload.

---

## Stage 2 — Backfill via `/settings/users`

For each legacy USER from Stage 1:

1. Log in as an admin, open `/settings/users`.
2. The page shows an amber warning banner with the count of unassigned USER rows.
3. For each row: click **Assign client** → pick from the datalist → add a short reason (e.g. "onboarding backfill — verified with ACME account manager") → **Save**.
4. Confirm the audit row appears in the **Recent admin actions** section immediately below the table.
5. If a USER should no longer have access, use **Deactivate** instead of assigning a client.

**Escalation:** if you're unsure which client a legacy USER belongs to, contact the account owner listed for that user's email domain, or deactivate + wait for the user to open a ticket.

**Target:** the amber "N USER row(s) still have no clientCode" banner reads `0` before proceeding.

---

## Stage 3 — Staging soak

Flip on staging first:

```bash
# staging deploy config
ACCESS_SCOPE_USER_BY_CLIENT=true
```

Deploy, then soak for **at least 3 business days** watching for:

**Monitoring queries** (run daily during soak):

```sql
-- Recent AccessDenied surprises. Any hit here means a scoped USER
-- ran into a service path we didn't clamp. Investigate before prod.
SELECT COUNT(*) FROM logs
WHERE message LIKE '%CROSS_TENANT_ACCESS_DENIED%'
  AND ts > NOW() - INTERVAL '24 hours';

-- Admin actions in the last 24h — sanity that the UI is being used.
SELECT COUNT(*), action FROM user_admin_audit
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY action;
```

**Application logs** — grep for:

- `CROSS_TENANT_ACCESS_DENIED` — a scoped USER hit a foreign tenant. Legitimate scoping OR a UX bug (client picker not clamping upstream).
- `ACCOUNT_DEACTIVATED` — deactivated user attempted login. Legitimate OR an in-flight session that needs to be told.

**QA acceptance:** with a test scoped USER (clientCode=ACME) logged in:

- [ ] Order list, order detail, tracking, void, and packing slip all work for ACME orders.
- [ ] `/orders?tenantId=OTHER` returns 403 with `CROSS_TENANT_ACCESS_DENIED`.
- [ ] `/orders/stats` shows ACME's numbers only, not org-wide.
- [ ] `/reports/orders.csv?customerNo=OTHER` returns 403.
- [ ] `/clients` shows only ACME (or empty if ACME hasn't been listed).
- [ ] Admin USER (no clientCode) still sees everything as before.

If any check fails or the AccessDenied count is non-zero for legitimate operations, **flip the flag OFF, investigate, ship a patch**. Do not proceed to prod.

---

## Stage 4 — Prod flip

Only after Stage 3 is clean:

1. Announce in ops channel: "Enabling tenant-scope flag in prod at HH:MM".
2. Update prod config:

   ```bash
   ACCESS_SCOPE_USER_BY_CLIENT=true
   ```

3. Deploy. Watch:
   - Application error rate (should be flat).
   - `CROSS_TENANT_ACCESS_DENIED` log rate (should be near-zero after the first hour).
   - Support inbox for "I can't see my orders" tickets from legacy USERs (means we missed a backfill — check the audit trail for that user).

4. **Rollback drill (test before you need it):** know how fast you can flip the flag back.

   ```bash
   ACCESS_SCOPE_USER_BY_CLIENT=false
   # deploy
   ```

   This is safe: `TenantScopeEnforcer` and `AccessScopePolicy` are pass-throughs when the flag is off, so scoped USER instantly reverts to org-wide. No data migration or code change required.

---

## Rollback

- **During staging soak** — flip flag OFF, investigate the AccessDenied, patch and re-attempt.
- **In prod within the first hour** — flip flag OFF (see Stage 4 rollback drill). Investigate and re-plan.
- **In prod after the first hour + real usage of scoping** — assess: rolling back means every scoped USER becomes org-wide again for the duration. Coordinate with product before flipping if any customer-visible scoping is now being relied on.

The flag is idempotent — flipping it OFF then ON again during the same session has no data effect.

---

## Known caveats (from PR body)

1. **`BulkLabelController`, `ManifestController`, `PickupController`, `OrderController#/multi-warehouse-label`** — controller-layer SpEL kept as `hasAnyRole('ADMIN','USER')` because SpEL can't inspect request body. Service layer clamps via `TenantScopeEnforcer`. The clamping happens before any DB write, so this is safe — but body-level rejection surfaces one call later than SpEL would.
2. **`PackingSlipServiceImpl.render`** — if any legacy order has BOTH `tenant_id` AND `cust_no` null, a scoped user hitting that order will get a 403. Audit before flip:

   ```sql
   SELECT COUNT(*) FROM label_batch WHERE tenant_id IS NULL AND cust_no IS NULL;
   ```

   Should be 0. If not, backfill `cust_no` on those rows.

3. **`CustomFieldServiceImpl.upsertValues`** — belt-and-braces guard uses optional bean wiring. In prod both beans exist, but this will be tightened to `required` in PR F.

---

## Post-flip cleanup (PR F, deferred)

Do not do this until PR E has soaked ≥ 2 weeks in prod with zero incidents:

- Flyway V6: `ALTER TABLE users ALTER COLUMN client_code SET NOT NULL` for `role IN ('USER','TENANT')` (ADMIN stays nullable).
- Remove public `/auth/signup` endpoint entirely (invites only).
- Remove the "USER without clientCode is org-wide" backward-compat branch from `AccessScopePolicy`.
- Remove the DB fallback from `JwtAuthenticationFilter` (every valid token now carries the claim after 90 days of token rotation).

---

## Contacts

- PR: https://github.com/maharana2006-tech/thb-shioai/pull/118
- Plan doc: memory file `plan_sprint50_tier05_authhardening.md`
- On-call: page whoever owns the auth stack — the flag flip is a security posture change, not a routine deploy.
