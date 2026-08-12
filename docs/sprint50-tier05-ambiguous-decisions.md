# Sprint 50 Tier 0.5 — ambiguous-item product decisions (conservative defaults)

**Status**: locked in via code as of the Sprint 50 hardening PRs. Product should review and either ratify or request the alternative in a follow-up ticket. Nothing here blocks the `access.scope-user-by-client=true` flag flip.

**Context**: the PR F audit ([`docs/sprint50-tier05-E-rollout-runbook.md`](sprint50-tier05-E-rollout-runbook.md)) identified 5 endpoints where the "correct" tenant-scoping behavior for a scoped USER was ambiguous — either could be the intended product behavior. Rather than block the audit-close on product review, each got a conservative default: whichever choice denies more, leaks less, or forces the operator to explicitly grant.

---

## Item #1 — `WarehouseController.listWarehouses(ownerClientCode)` query param

**Endpoint**: `GET /api/v1/warehouses?ownerClientCode=<code>`

**Ambiguity**: is warehouse master-data operator-only regardless of ownership, or should a scoped USER see warehouses owned by their own client?

**Decision**: **scoped USER sees warehouses for their own client only** (bucket B in PR F terms). The SpEL was tightened to `hasAnyRole('ADMIN','USER') and (#ownerClientCode == null or @accessScope.canAccessTenant(authentication, #ownerClientCode))` in PR #126.

**Why conservative**:
- A scoped USER querying without the param would previously see every tenant's warehouses. That's the leak.
- Scoping the filter to their own tenant matches the "USER sees own tenant's data" mental model established for orders, clients, and every other client-scoped resource.
- If product decides operators should be the ONLY consumers, tightening further is a one-line SpEL change (`hasRole('ADMIN')`); loosening it back to org-wide would need a code change AND a security review.

**Loosen path if product disagrees**: change SpEL back to `hasAnyRole('ADMIN', 'USER')` and add a docs note that master-warehouse data is intentionally cross-tenant.

---

## Item #2 — `AccountRefController.upsertAccount` sharing model

**Endpoint**: `POST /api/v1/carrier-accounts` (with `request.customerNo` in body)

**Ambiguity**: is the carrier account book intentionally a shared operator resource (any operator manages any tenant's accounts), or should a scoped USER only create/update accounts under their own clientCode?

**Decision**: **scoped USER limited to own tenant** — `AccountRefServiceImpl.upsertAccount` clamps `request.customerNo` in PR #129, and rejects a natural-key hijack (existing `(accountNumber, carrierCode)` row belonging to a different tenant → 403).

**Why conservative**:
- Carrier accounts carry payment credentials. Cross-tenant write access from a scoped USER means a compromised tenant login can silently reassign another tenant's default carrier account (findings H1 in the audit).
- Operators still have full cross-tenant access (they pass through the clamp).
- Aligns with how every other client-config resource behaves (`/clients/{clientCode}/**` in PR #126).

**Loosen path**: remove the clamp in `AccountRefServiceImpl.upsertAccount` + document that scoped USER can create accounts against foreign clientCodes. Would need explicit product decision + audit sign-off — the account book has payment-touching implications.

---

## Item #3 — `DashboardController` tenant-awareness

**Endpoint**: `GET /api/v1/dashboard`

**Ambiguity**: for scoped USER, should the aggregate dashboard show tenant-scoped numbers (labels for their own client only), or is it explicitly an operator view (scoped USERs shouldn't see it at all)?

**Decision**: **tenant-scoped dashboard for scoped USER**. `DashboardService` was made tenant-aware in PR #129 (H) — every raw-JDBC aggregate clamps `AND UPPER(COALESCE(tenant_id, cust_no)) = UPPER(:scope)` when `tenantScope.resolveScope().isPresent()`. Operators keep the org-wide roll-up.

**Why conservative**:
- Denying scoped USER access entirely (bucket A) would break their landing page — 403 on `/dashboard` after login is a poor UX.
- Scoping to own tenant is what every other aggregate does (`getQueueStats`, `getOrdersStats`).
- The alternative — showing org-wide numbers to a scoped USER — was the actual leak the audit flagged (they'd see the 3PL's total volume, per-carrier split, etc.).

**Loosen path**: change to bucket A (operator-only) and update the frontend to render an "unavailable for this account" state on `/dashboard` for scoped USER role. Requires frontend cooperation.

---

## Item #4 — `OrderController#/queue-stats` and `#/stats` tenant-awareness

Was flagged as ambiguous by the audit; **already resolved in PR #127 (G)** — both `getQueueStats` and `getDashboardStats` on `OrderServiceImpl` dispatch through `tenantScope.resolveScope()` to per-tenant repo variants (`getQueueStatsForTenant`, `getDashboardStatsForTenant`). Same rationale as item #3.

No product review needed — this is already the same pattern the rest of the system uses.

---

## Item #5 — `OrderImportController.templateXlsx?accountId=<n>`

**Endpoint**: `GET /api/v1/orders/import/template.xlsx?accountId=<n>`

**Ambiguity**: the XLSX template embeds account-scoped dropdowns. Should a scoped USER only be allowed to download a template for one of their own client's accounts?

**Decision**: **NOT tightened** — template download stays operator-plus-USER without account-ownership check. Rationale below.

**Why left alone**:
- The template is a generation aid, not a data leak — it contains dropdown values (accountId → account number, service codes, package presets). No PII, no credentials, no order data.
- The consumer of the template (an ops user filling in rows) then submits via `/orders/import/preview` and `/commit`, both of which ARE tenant-clamped in PR #129. Any foreign-account values a scoped USER coerces into the template get rejected at commit.
- Tightening would need `AccountRefRepository.findById(accountId) → requireTenantMatch(account.getCustomerNo())` in `OrderImportServiceImpl.xlsxTemplate`. Small change, but no observed leak justifies the extra roundtrip.

**Tighten path if product disagrees**: 5-line addition to `OrderImportServiceImpl.xlsxTemplate` (load account by id, requireTenantMatch on `customerNo`). Ship as a small follow-up.

---

## Item #6 — `WebhookSubscriptionAdminController` USER visibility

**Endpoint**: `GET /api/v1/webhook-subscriptions?apiKeyId=<n>`

**Ambiguity**: should a scoped USER see webhook subscriptions at all? Currently bucket A (`hasAnyRole('ADMIN','USER')`) — org-wide.

**Decision**: **NOT tightened** — kept as-is. Secrets are already masked in the response (`ExternalWebhookSubscriptionDTO.from(s, true)` from PR #118); mutations are `hasRole('ADMIN')` only.

**Why left alone**:
- The audit's concern was URL disclosure ("integration paths"). Fair, but the audit itself rated it LOW-MEDIUM.
- Deployment of webhooks is an ops function; scoped USERs occasionally need visibility to understand why an event fired against their tenant.
- Tightening would break tenant-side visibility of their own webhooks (with no gain since mutations already require ADMIN).

**Tighten path**: change controller SpEL to `hasRole('ADMIN')` — lockdown-mode UX for the webhook admin page. If product prefers, revisit.

---

## Summary — what this doc gives you

| # | Item | Default | Product action if disagreeing |
|---|---|---|---|
| 1 | `WarehouseController` `ownerClientCode` | Scoped USER sees own-tenant | Loosen SpEL back to org-wide OR tighten to ADMIN-only |
| 2 | `AccountRefController.upsertAccount` | Scoped USER limited to own | Remove clamp (needs security sign-off — payment surface) |
| 3 | `DashboardController` | Scoped USER sees own aggregates | Change to ADMIN-only + frontend "unavailable" state |
| 4 | Order stats endpoints | Scoped USER sees own aggregates | Already-aligned, no decision needed |
| 5 | `OrderImportController.templateXlsx` | Operator+USER, no account clamp | 5-line clamp addition |
| 6 | `WebhookSubscriptionAdminController` | Operator+USER, secrets masked | Tighten to ADMIN-only |

Each row has a "loosen/tighten path" — one-PR change if product wants the alternative. Until then the conservative default is what ships.
