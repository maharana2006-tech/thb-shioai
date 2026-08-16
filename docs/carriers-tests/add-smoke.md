# Carriers · Add / Connect flow — manual smoke

Slice owner: `test(carriers-fe-add)`. Automated coverage lives in
`multiship-react/src/components/CarrierConnections.add.test.tsx`. This
document captures the same flow for a human running against a live
backend, so a QA pass doesn't have to reverse-engineer the drawer.

## Pre-flight

- Logged in as `ADMIN` (the Add button is not exposed to `USER`
  by role today, but the button is unconditionally rendered in the
  toolbar — see the "Role visibility" section below).
- Backend reachable; UPS / FedEx / USPS credentials at hand for a
  real end-to-end save. For a validation-only sweep, dummy strings
  are fine — nothing is persisted until the API call succeeds.

## Path

1. Open **Settings → Carriers** (`/settings/carriers`).
2. Toolbar → click **Add Account**. The right-side drawer opens with
   the header "Add carrier account" and four numbered steps
   (Account type, Carrier, Credentials, Verify & save).
3. Step 1 — leave **Account type** on "Platform account" (default).
4. Step 2 — pick **UPS**. Notice the credential labels in Step 3
   retitle from generic "Client ID / Client Secret" to UPS's
   "Consumer Key / Consumer Secret". Repeat with FedEx / USPS / DHL
   to spot-check the label swap.
5. Step 3 — fill:
   - **Account name**: `Smoke Test UPS`.
   - **Account number**: `740561111` (any 6–10 alphanumeric passes
     UPS's client-side pattern; the server does the real check).
   - **Consumer Key**: paste from developer.ups.com.
   - **Consumer Secret**: paste from developer.ups.com.
   - **Environment**: leave at `SANDBOX` for a smoke run.
6. Step 4 (optional) — click **Run verification**. Expect a green
   "Verified" chip on success or a red "Check failed" pill with the
   backend message on failure.
7. Click **Save to Account Book**. Expect a green toast
   "Account 740561111 saved to the account book." and the drawer
   closes. The account appears in the table.

## Validation cases to hit

- Blank account number → save shows "Account number is required"
  inline; drawer stays open.
- UPS + `abc` account number → save shows
  "UPS shipper number is 6–10 letters or digits".
- FedEx + `12345` account number → save shows
  "FedEx account number is exactly 9 digits".
- Consumer Key contains a space (`abc def`) → save shows
  "Remove spaces — paste the key exactly as issued".
- Empty Consumer Key or Consumer Secret → save shows
  "<field> is required".
- Backend down → save produces a red error toast via
  `notify.apiError`; drawer stays open so the operator can retry.

## Cancel path

- Open the drawer, type an account number, click **Cancel** in the
  footer. The drawer closes; the table is unchanged and no request
  is fired.

## Role visibility

- ADMIN: sees the Add button and can complete the flow.
- USER / TENANT: the button is rendered but the backend rejects the
  `POST /carrier-accounts` with 403 today. This is documented behavior
  — the UI-side hiding is tracked as follow-up work if the product
  team wants role-scoped visibility. No blocking bug filed.

## Related automated coverage

- 13 tests in `CarrierConnections.add.test.tsx`:
  6 positive + 6 negative + 1 client-account path. All services
  mocked; a global `fetch` spy in `beforeEach` throws on any
  un-mocked network call so nothing slips past the mocks.
