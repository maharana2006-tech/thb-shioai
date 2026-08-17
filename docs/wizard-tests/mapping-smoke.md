# Client Wizard — Mapping Step Manual Smoke

Step owner: wizard test agent (Mapping). Route: `/settings/clients/new` (create)
and `/settings/clients/:code` (edit).

The wizard's Mapping step routes an order's ship-via code (e.g. `P80`) to a
carrier service (`serviceId`). In **create mode** the step captures a staged
list of `mappingDrafts` that fan out to `shippingConfigService.saveRule` per
draft after Submit fires (see `handleCreate` in `ClientEditorPage.tsx`). In
**edit mode** the wizard delegates to `ClientShippingMappingTab` for the full
per-rule editor (destinations, warehouses, package allowlist).

Prereqs before the Mapping step is reachable in create mode:

- Identity, Ship From, Return, and Carriers steps must all be complete.
- ≥1 staged carrier draft OR a picked platform carrier account is required
  before the carrier-service dropdown will surface any options.

## Create-mode checklist

Land on `/settings/clients/new` as a fresh operator. Walk the wizard through
Identity → Ship From → Return → Carriers and stage at least one carrier
account, then land on step 5 (Mapping).

- [ ] The Mapping panel renders with the header **"Shipping service mapping (draft)"**
      and a descriptive paragraph pointing at post-create scope editing.
- [ ] With zero drafts staged, the empty state card **"No mappings staged yet"**
      is visible and an **"Add mapping"** button appears in the top-right.
- [ ] Clicking **"Add mapping"** opens an inline draft form with three
      controls: Order Ship Via, Platform carrier account, Carrier Ship Via.
- [ ] Typing a lowercase code (e.g. `p80`) into **Order Ship Via** normalizes
      to uppercase (`P80`) in the input immediately.
- [ ] The **Carrier Ship Via** dropdown only offers services whose carrier
      matches at least one staged carrier account. Services for other
      carriers must NOT appear.
- [ ] Disabled catalog services (server-side `enabled=false`) never surface.
- [ ] Selecting a **Platform carrier account** narrows the Ship Via list to
      include that platform account's carrier services on top of the
      client's own staged carrier services.
- [ ] Filling all three fields with valid values and clicking **"Add to list"**:
    - closes the form
    - appends a new row containing the shipviaCd chip and the carrier +
      service-name label
    - the row exposes a `Remove <SHIPVIACD>` button
- [ ] Multiple drafts stage side-by-side; each has a unique `id` (React key)
      and its own Remove button.
- [ ] Clicking Remove on a draft row deletes only that row and, when it was
      the last one, restores the "No mappings staged yet" empty state.
- [ ] Advancing to Summary shows every staged mapping in the review card.
- [ ] Submit fires — `handleCreate` calls `shippingConfigService.saveRule`
      once per staged mapping with `{ shipviaCd, clientCode: <new>, serviceId,
      destType: 'ANY', destValue: null, warehouseIds: [], allowedPresetIds: [] }`.
- [ ] The success toast reports `"N mappings saved."` and the wizard
      redirects to `/settings/clients/<newCode>` with `advanceTo: 'carriers'`.

## Create-mode gating (Next / Submit)

- [ ] With zero mapping drafts, the wizard's **Next** button is disabled on
      the Mapping step and its tooltip includes the blocker text
      **"Add at least one shipping-service mapping"**.
- [ ] With zero drafts, the Summary step's **Submit** button is disabled and
      its tooltip lists the same blocker.
- [ ] Skipping ahead by clicking the Mapping pill without completing Carriers
      surfaces the error toast **"Complete 'Carrier accounts' before jumping
      ahead."**

## Draft form validation

- [ ] Leaving **Order Ship Via** empty and blurring the field surfaces
      **"Order Ship Via is required."**.
- [ ] Entering only separator characters (e.g. `--`) surfaces
      **"Order Ship Via must include at least one letter or digit."**.
- [ ] Entering characters that violate `[A-Z0-9_-]` (e.g. a space in `A B`)
      surfaces **"Only letters, digits, '-' and '_' are allowed (no spaces)."**.
- [ ] With no service picked, the inline error **"Pick a carrier service."**
      appears after the field is touched.
- [ ] When the client has NO staged carriers AND no platform account is
      picked, the Platform-carrier-account label reads `· required` and
      the error **"Pick a platform account to source the carrier."** shows.
- [ ] When the client has ≥1 staged carrier, the Platform label reads
      `· optional` and no error surfaces for an empty platform pick.
- [ ] The **"Add to list"** button is `aria-disabled="true"` (with a
      gray background) when any required field is invalid; clicking it
      force-touches every field so all errors surface, and never appends
      a row while invalid.
- [ ] When no carriers are staged AND no platform account is picked, the
      Carrier Ship Via placeholder reads **"Add a carrier account first —"**.

## Draft persistence

- [ ] Add 2 drafts, close the tab, reopen `/settings/clients/new` — both
      drafts still appear (persisted under `clientEditorDraft:<user>` in
      localStorage).
- [ ] Successful Submit clears the local draft (`localStorage.removeItem`).

## Edit-mode checklist

Open `/settings/clients/<ACME>` for a persisted client.

- [ ] The Mapping pill is directly clickable (edit mode disables the
      sequential lock).
- [ ] Clicking it renders `ClientShippingMappingTab` inside
      `#client-editor-panel-mapping` with the full per-rule editor
      (Order Ship Via → Warehouse → Ship to → Carrier Ship Via → Packages).
- [ ] The persisted mapping rules for the client render as table rows.

## Bugs found in this pass

None. See PR body / linked issues for any surface issues discovered during
the automated pass.
