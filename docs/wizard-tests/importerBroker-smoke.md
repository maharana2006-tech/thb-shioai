# Wizard smoke — Importer / Broker step

Manual QA checklist for the create-mode `ImporterBrokerDraftStep` (Step 6 of
`/settings/clients/new`) and the edit-mode `ImporterBrokerStep`
(`/settings/clients/{code}` → "Importer" tab). Run through this after any
change to `ClientEditorPage.tsx`, `customsProfileService.ts`, or the
CustomsProfileModal.

Companion to `multiship-react/src/components/ClientEditorPage.importerBroker.test.tsx`
(22 automated cases). This doc covers the human-in-the-loop pieces the unit
tests intentionally do not exercise (toast copy, real modal focus, backend
round-trip).

## Preconditions

- Local backend running on `:8080`, seeded with at least one CLIENT that has
  no existing customs profiles (for the edit-mode create path) and one that
  does (for the edit-mode list + edit + remove paths).
- Logged in as an operator with `CLIENT_WRITE` permission.

## A. Create-mode draft step (unfilled — the happy skip)

1. Open `/settings/clients/new`.
2. Walk through Identity → Ship From → Return → Carriers → Mapping filling
   the minimum required fields on each step (skip warehouses / packages —
   they've been removed from the wizard).
3. Land on Step 6 "Importer / Broker".
4. **Expected**: the guidance card shows "Fill Importer / Broker now"
   unchecked, plus a paragraph explaining the step is optional.
5. **Expected**: no form fields are rendered below the checkbox.
6. Click **Next** in the footer.
7. **Expected**: the wizard advances to Step 7 (Summary) without error.
8. In the Summary panel, the Importer / Broker card carries an amber
   **SKIPPED** pill (not red — skipped-is-valid).
9. Click **Submit — create client**.
10. **Expected**: client is created. No importer-broker profile is created.
11. Confirm via the Settings → Importer/Broker page: no profile row for the
    freshly-created client.

## B. Create-mode draft step (filled — BUSINESS)

1. Repeat A.1–A.3 to land on Step 6.
2. Tick **Fill Importer / Broker now**.
3. **Expected**: the form appears with two radio pills
   (BUSINESS · fixed importer / RECEIVER · consignee is IOR) with BUSINESS
   preselected, then the BUSINESS identity block, then Shipment defaults,
   then Broker section.
4. Fill:
   - Importer name: `Acme Imports Ltd`
   - Country (ISO-2): type `gb` — **must uppercase to `GB`** live.
   - Address line 1: `12 Wharf Rd`
   - City: `London`
   - Postal code: `EC1 1AA`
   - Incoterms: pick `DDP`
   - Reason for export: pick `SALE`
   - Broker name: `LOGiPro Brokers` (broker phone optional)
5. **Expected**: the wizard Next button is enabled (no tooltip).
6. Advance to Summary.
7. **Expected**: Importer / Broker card is green **READY** with the importer
   name, country line, and `DDP · SALE` on the defaults row.
8. Click **Submit — create client**.
9. **Expected**: two success toasts fire in sequence:
   - `Client created.` (from clientService)
   - `Importer / broker profile saved.` (from customsProfileService)
10. Navigate to Settings → Importer/Broker; verify the new profile row
    appears with the entered values.

## C. Create-mode draft step (filled — RECEIVER / DAP)

1. Repeat A.1–A.3, tick the checkbox.
2. Click the **RECEIVER · consignee is IOR (DAP)** radio.
3. **Expected**: the BUSINESS identity block disappears entirely (name,
   country, address, city, postcode). Only Shipment defaults + Broker
   remain.
4. Leave everything blank; **Next** should still be enabled — RECEIVER
   profiles do not require any importer identity of their own.
5. Advance to Summary → Submit.
6. **Expected**: profile is saved with `importerType=RECEIVER` and no
   importer identity fields.

## D. Create-mode blocker tooltip (BUSINESS missing required field)

For each missing field: importer name, country, address 1, city, postcode.

1. Tick the checkbox, keep importer type = BUSINESS.
2. Fill four of the five required fields, leave the target field empty.
3. **Expected**: Next is disabled (grey).
4. Hover Next.
5. **Expected**: tooltip text starts with "Complete this step to continue:"
   and lists "BUSINESS importer needs name, country, address, city and
   postal code — or switch to RECEIVER, or uncheck 'Fill importer/broker'."
6. Uncheck the "Fill Importer / Broker now" checkbox.
7. **Expected**: Next becomes enabled immediately (the step is now a valid
   skip regardless of the half-filled draft's state).
8. Re-check the checkbox.
9. **Expected**: the values you'd previously entered are still there — the
   draft is preserved across the toggle.

## E. Draft persistence across a reload

1. On `/settings/clients/new`, walk to Step 6.
2. Tick the checkbox and type `Persisted Co` in the Importer name.
3. Hard-reload the browser tab (Ctrl+Shift+R).
4. **Expected**: the wizard re-opens on Step 6, checkbox still ticked,
   Importer name still shows `Persisted Co`.

## F. Edit mode — list, add, edit, remove

1. Open an existing client via `/settings/clients/{code}`.
2. Click the **Importer** tab in the wizard rail.
3. **Expected**: `customsProfileService.list(clientCode)` fires; while
   loading, the "Loading…" placeholder is visible; once loaded, either:
   - "No importer / broker profiles yet" empty state with an "Add first
     profile" CTA, OR
   - a list of profiles showing name, type pill, coverage country pills,
     Edit + delete buttons.
4. Click **Add profile** — the CustomsProfileModal opens with the client
   pre-selected and locked. Fill the required fields and save.
5. **Expected**: modal closes, success toast fires, list refreshes with the
   new row.
6. Click **Edit** on a row; modify a field; save. Row updates in place.
7. Click the delete (×) button on a row; confirm the dialog.
8. **Expected**: success toast fires, row disappears.

## G. Error paths (edit mode)

1. In DevTools Network, throttle `/customs-profiles` GET to fail (block
   URL).
2. Reopen the Importer tab.
3. **Expected**: error toast "Failed to load importer / broker profiles."
   surfaces; the list panel does not render.

## Bug reporting

If any step above deviates from the expected behavior, file a bug via
`gh issue create --label wizard-tests-bug --title "..." --body "..."` and
link it back to this doc so the next QA cycle can regression-test the fix.
