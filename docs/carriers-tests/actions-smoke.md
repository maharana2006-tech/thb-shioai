# /settings/carriers — Per-account actions manual smoke

Sprint 52 · 2026-08-16 · Owner: FE test agent (actions slice)

**Scope.** Every per-account action exposed on the account row of
`/settings/carriers`: the kebab menu (Verify / Edit / Delete), the
in-row active toggle in the Status cell, and the client-default star in
the Carrier cell.

**Automated coverage.** `multiship-react/src/components/CarrierConnections.actions.test.tsx`
(14 tests). Every backend service call is mocked. A `global.fetch` guard
throws on any un-mocked network attempt so a regression that removed a
service mock surfaces as a red test rather than a silent live carrier hit.

## Pre-flight

- Sign in as an **ADMIN** user (the kebab menu is authored to render the
  same items regardless of role today — enforce role gating at the
  wrapper if you add USER/READ_ONLY tests).
- Ensure at least three accounts exist so you can exercise all paths:
  1. A platform-owned UPS account (`customerNo` empty) — verified.
  2. A client-owned UPS account for a client (e.g. `ACME`) — active,
     complete, non-default, **zero labels generated** (needed for
     Delete path).
  3. A client-owned FedEx account for the same client — active,
     complete, **labelsGenerated > 0** (needed for the delete-disabled
     path).

## Cases

| # | Case | Steps | Pass criteria |
|---|------|-------|---------------|
| 1 | **Edit — prefill** | Row kebab → Edit on the UPS platform account. | Drawer opens titled `Update <accountNumber>`; Account name input shows the saved value; Account number field is read-only. |
| 2 | **Edit — save** | From (1), change account name, click **Save to Account Book**. | POST /carrier-accounts fires once; toast `Account <n> saved…`; drawer closes; row reflects new name after refetch. |
| 3 | **Verify — success** | Row kebab → **Verify**. Carrier creds valid. | POST /carrier-accounts/{id}/verify fires once; success toast; row status chip flips to **Verified** (or stays if already verified). |
| 4 | **Verify — failure** | Row kebab → **Verify** on an account whose OAuth creds were rotated/invalidated. | Error toast surfaces; row shows **Check failed** chip; account stays in the list (verification failure is non-fatal). |
| 5 | **Verify — network reject** | Kill backend connectivity, click Verify. | notify.apiError surfaces (friendly message); row remains; single retry call — no fallback duplicate request. |
| 6 | **Verify — hidden on incomplete** | Row is `complete=false`. | Kebab shows only **Edit** + **Delete** — no Verify item (verifiable = complete && active). |
| 7 | **Toggle active — deactivate** | Click Status-cell switch on an active row. | POST /toggle-active fires; toast `Account deactivated.`; row Status pill flips to **Inactive**. |
| 8 | **Toggle active — reactivate** | Click switch on inactive row. | Toast `Account activated.`; row Status pill returns to Verified/Unverified. |
| 9 | **Toggle active — error** | Simulate 5xx (e.g. block the request in DevTools). | notify.apiError; row state does not change; no success toast. |
| 10 | **Delete — accept confirm** | Row kebab → **Delete** on the zero-labels account → OK. | window.confirm fires; DELETE /carrier-accounts/{id} fires once; toast `Account <n> removed…`; row disappears. |
| 11 | **Delete — cancel confirm** | Same as (10) but click **Cancel** in the browser confirm. | No DELETE call; row stays; no toast. |
| 12 | **Delete — disabled by usage** | Open kebab on the FedEx account with labelsGenerated > 0. | Delete menuitem is rendered but disabled with a tooltip about deactivating instead. |
| 13 | **Mark client default** | Click the empty (grey) star in the Carrier cell of an active client account. | POST /client-default fires; toast `<accountNumber> is now <client>'s default account.`; star turns gold on this row. |
| 14 | **Concurrency / busy gate** | Rapidly click Verify, then immediately try to click the Status-cell toggle or open the kebab and click Delete. | The second action is blocked (busyId set) — the toggle is disabled, Delete menuitem disabled. Once the first action resolves, controls re-enable. |

## Anti-fallback

- The test suite's fetch guard (`global.fetch` throws `un-mocked fetch forbidden`)
  makes it impossible for a test to accidentally hit a real carrier or the
  backend. If you add a code path that touches a new service, mock it or
  extend the setup — do not remove the guard.
- `verifyAccount` is asserted to be called **exactly once** per test path
  so a defensive retry loop cannot silently mask a broken UI wire.
