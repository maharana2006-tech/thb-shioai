# 04 — Rotate carrier credentials (7 accounts)

The dumped `carrier_account_ref` table has 7 rows. Each holds:
- `client_id` / `account_number` (identifiers — not secret alone but useful for pretexting)
- `client_secret` (AES-GCM at rest — treat as plaintext until 03 confirms otherwise)
- `access_token` (short-lived OAuth token; may be expired already)

**Rotate the credentials at the carrier, then re-save in the app.** Do NOT
just re-encrypt the existing `client_secret` — the plaintext is out.

## Per-carrier steps

Enumerate the exact 7 accounts first:
```sql
SELECT id, carrier_code, client_code, account_number, environment, active
  FROM carrier_account_ref
 ORDER BY carrier_code, client_code;
```

Then for each row, follow the appropriate carrier flow below.

### UPS
1. https://developer.ups.com/apps → your app
2. **Rotate secret**: your app card → "Regenerate secret". You get one
   new `client_secret`; the old one is invalidated immediately.
3. In the ShipX admin UI: Settings → Carriers → the UPS account → Edit
   → paste the new secret → Save (encrypts + persists via CryptoService).
4. Sanity-check: try a rate-shop for a known-good address.

### FedEx
1. https://developer.fedex.com/ → Projects → your project
2. **Rotate API Key + Secret Key**: some flows require deleting +
   re-creating the credential pair. Keep the old one alive until step
   3 saves the new one.
3. Update via admin UI (same flow as UPS above).

### DHL Express
1. https://developer.dhl.com/ → Apps
2. **Rotate**: the DHL portal issues a new key pair on request.
3. Same admin-UI update.

### USPS / Stamps.com (SWSIM)
1. https://developer.stamps.com/ (or the older SWSIM console)
2. **Rotate integration ID + password**. SWSIM uses the integration
   password as the bearer credential — rotate that specifically.
3. Same admin-UI update.

## Batch rotation script (advanced)
If you have carrier-portal API access to rotate programmatically, you
could script this. In practice all four carriers require human portal
steps for secret regeneration. Human-in-the-loop is the pragmatic path.

## After rotation

### Verify
Every account should be usable end-to-end:
```
Settings → Carriers → click each account → "Test connection"
```
That hits the carrier's auth endpoint and reports success/failure. If
any fail: re-check that you saved the correct secret in the admin UI.

### Force any cached tokens to refresh
The app caches `access_token` on the row until it expires. Force-refresh:
```sql
UPDATE carrier_account_ref
   SET access_token = NULL,
       access_token_expires_at = NULL;
```
Next carrier call rebuilds the token via the newly-rotated credentials.

### Notify any partners
If any carrier account was shared with a downstream integration
(3PL / marketplace connector), tell them. Their cached credentials
just stopped working.
