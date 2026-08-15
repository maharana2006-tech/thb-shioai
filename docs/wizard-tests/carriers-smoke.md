# Client Wizard — Carriers step smoke test

Manual QA checklist for the **Carriers** step of the client add/edit wizard
(`ClientEditorPage` → `CarrierDraftStep` in create mode, `CarrierConnections`
in edit mode). Executed against the deployed environment after any change to
the wizard, `accountRefService`, or per-carrier account-number rules.

## Pre-req

- Log in as an ADMIN (`carrier-accounts` write scope).
- Note the target tenant / client code you intend to create.
- Have a sandbox credential set on hand for at least UPS + FedEx.

## Create mode — happy path

1. Navigate to `/settings/clients/new`.
2. Fill Identity (code + name) → click Next.
3. Fill Ship From (pick a warehouse) → click Next.
4. Return address: leave "same as ship from" ticked → click Next.
5. **Carriers step opens.** Verify:
   - Empty state banner reads "No carrier accounts staged yet".
   - "Add carrier account" primary button is visible top-right.
6. Click **Add carrier account**. Verify:
   - Draft form appears with Carrier select defaulting to UPS.
   - Account number / Client ID / Client Secret inputs render.
   - Environment select defaults to SANDBOX and offers PRODUCTION.
   - "Default account" checkbox is pre-checked (first draft is default).
   - Shipping purpose + Customs clearance selects render with a "carrier default" option.
7. Type an invalid account number (e.g. `12345` for UPS which requires 6 alphanumerics) then blur.
   Verify inline red error under the field.
8. Fix the account number to `ABC123`. Type a placeholder Client ID + Secret ≥ 6 chars.
   The "Add to list" button becomes active.
9. Click **Add to list**. Verify:
   - Form collapses.
   - New draft appears in the list: `UPS · ABC123` with the "default" star pill.
   - Environment badge reads `SANDBOX`.
10. Click **Add carrier account** again.
    - Change Carrier to FedEx.
    - Verify account number placeholder + max-length update (12 digits max).
    - Enter `123456789` for account number (9 digits).
    - Enter Client ID + Secret; leave "Default account" unchecked.
    - Click "Add to list" → verify the FedEx row lands without a default star.
11. Delete the FedEx row (X button). Verify it disappears; UPS row remains.
12. Change Customs clearance to `Third party` on a new draft. Verify third-party billing panel appears with account #, name, address fields.

## Create mode — negative cases

1. Attempt to click **Next** on the Carriers step with zero drafts. The button is disabled;
   tooltip lists "Add at least one carrier account".
2. Open the Add form. Blur the account number without typing → error line + "Add to list" disabled state.
3. Enter Client ID with spaces (e.g. `abc def`). Confirm error: "Remove spaces — paste the key exactly as issued".
4. Enter Client Secret shorter than 6 chars → error surfaces on blur.
5. Cancel the draft form. Confirm errors clear and the form closes without adding.

## Cross-cutting

1. Add UPS + FedEx drafts. Reload the page → confirm both drafts still appear
   (per-user localStorage restores the wizard state).
2. Fill through Mapping + Summary and click **Submit — create client**. Verify:
   - Success toast for client + a `2 carrier accounts saved` toast.
   - Both accounts appear under Settings → Carriers for the new client.
3. Re-open the newly-created client (`/settings/clients/{code}`) → Carriers step.
   The live `CarrierConnections` panel is shown embedded, listing both accounts.

## Edit mode

1. Open an existing client with two accounts. Verify both rows hydrate.
2. Add a third account via the drawer; confirm it saves and appears in the list.
3. Toggle default flag on a non-default account; confirm the star migrates.
4. Delete an unused account (no labels generated). Confirm success + row removed.

## Owner

Frontend QA — Sprint 52 wizard test track (Carriers agent).
