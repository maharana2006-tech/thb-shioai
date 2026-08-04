# Order-import template — optional VBA macros

Sprint 48 shipped a POI-generated `.xlsx` template (dropdowns + sample rows +
instructions sheet) at `GET /api/v1/orders/import/template.xlsx`. That is the
recommended path for most operators — no macros, portable across Excel /
LibreOffice / Google Sheets.

The four `.bas` files in this directory are **optional add-ons** for teams that
want an `.xlsm` variant with in-workbook buttons for:

| Macro              | Button label         | What it does |
|--------------------|----------------------|--------------|
| `SaveAsCsv`        | *Save as CSV*        | Writes a UTF-8 CSV next to the workbook — plain RFC-4180 escaping, no `=` armour, BOM prefix. |
| `ValidateAll`      | *Validate Data*      | Two-phase check. Phase 1 (local): required fields, billTo enum, HS shape, ISO-2 country codes, integer qty, international-item rule. Phase 2 (network): POSTs current rows to `/api/v1/orders/import/validate` and merges backend errors + warnings. Colour-codes red / yellow / green. |
| `ValidateAddresses`| *Validate Addresses* | POSTs current rows to `/api/v1/orders/import/validate-addresses`; backend calls each row's picked carrier's address-validation API. Paints recipient / address cells yellow when the carrier flags the address. |
| `BackendConfig`    | *(no button)*        | Shared helpers for the two backend-calling macros: `BackendUrl()` reads from `Reference!Z1` with a fallback prompt; `BearerToken()` prompts once per session and caches. Import this **first** so ValidateAll / ValidateAddresses resolve their calls. |

### Setting the backend URL

The two backend-calling macros read the URL from `Reference!Z1`. To set it once
per environment:

1. In Excel, right-click any sheet tab → **Unhide** — Reference won't appear
   because it's `state=veryHidden`. Instead: Alt+F11 → in the VBA project
   properties for the Reference sheet, set `Visible` from `xlSheetVeryHidden`
   to `xlSheetVisible`.
2. Go to Reference sheet, cell `Z1`, paste your backend URL
   (e.g. `http://localhost:8080` or `https://ship.example.com`).
3. Flip Visible back to `xlSheetVeryHidden`.
4. Save the .xlsm.

If `Reference!Z1` is empty, `BackendConfig.BackendUrl()` falls back to the
`DEFAULT_BACKEND` constant in the .bas source (currently `http://localhost:8080`)
or prompts the operator on first macro run.

## One-time setup — inject macros into an `.xlsm`

Author the `.xlsm` once, drop the file at
`backend/src/main/resources/templates/order-import-template.xlsm`, and follow
up with a small backend change so the download endpoint serves `.xlsm` when
present:

1. **Download the generic .xlsx template** from the app (Import modal →
   Download XLSX with no account picked).
2. **Open in Excel** → *Save As* → **Excel Macro-Enabled Workbook (.xlsm)** —
   save into the resources path above.
3. **Alt+F11** to open the VBA editor.
4. In the VBA editor: **File → Import File** → import all four `.bas` files.
   Order matters for one of them: import **`BackendConfig.bas` first** so
   `ValidateAll` + `ValidateAddresses` can call into it. Then `SaveAsCsv.bas`,
   `ValidateAll.bas`, `ValidateAddresses.bas`. Each becomes its own Module.
5. Back on the Import sheet: **Developer → Insert → Button (Form Control)**
   → draw three buttons and assign each to a macro:
   - *Save as CSV* → `SaveAsCsv`
   - *Validate Data* → `ValidateAll`
   - *Validate Addresses* → `ValidateAddresses`
6. Optional: set `Reference!Z1` to your backend URL (see "Setting the
   backend URL" above). If skipped, the macros fall back to
   `http://localhost:8080` or prompt the operator on first run.
7. **Save** the workbook (still as `.xlsm`).
8. Backend already prefers the .xlsm on generic downloads (accountId=null).
   The controller accepts either `order-import-template.xlsm` or the
   `-generic.xlsm` variant.

## Notes / limits

* The Excel-native "Save As CSV UTF-8" flow also works. The `SaveAsCsv`
  macro just automates the folder + encoding + escaping so operators
  don't have to think about it.
* `ValidateAll` never blocks the operator — it only paints cells. The
  backend commit step is the source of truth for validation.
* Both macros require **Trust access to the VBA project object model**
  ONLY IF an admin wants to programmatically inject them via POI (via
  `XSSFWorkbook.setVBAProject`). Regular users clicking the buttons do
  not need that permission.
* Neither macro touches anything outside the `Import` sheet.
* `SaveAsCsv` writes next to the workbook (`.xlsm` → same folder, same
  base name, `.csv` extension). If the workbook is on a read-only share,
  the write silently fails — the macro then shows an error dialog with
  the target path so the operator knows why.

## Why we didn't ship the .xlsm directly

Generating a valid `.xlsm` requires embedding a binary `vbaProject.bin`
inside the OOXML zip. Our build tooling ships `.xlsx` fine (POI's
`XSSFWorkbook`) but authoring a `vbaProject.bin` from scratch is
non-trivial and would drag in either a pre-authored binary blob or a
sizeable dependency to synthesize one. Shipping the `.bas` source and
letting an admin do the one-time injection keeps the repo text-only and
avoids the security-scan flags that come with macro-enabled binaries.
