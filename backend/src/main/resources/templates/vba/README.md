# Order-import template — optional VBA macros

Sprint 48 shipped a POI-generated `.xlsx` template (dropdowns + sample rows +
instructions sheet) at `GET /api/v1/orders/import/template.xlsx`. That is the
recommended path for most operators — no macros, portable across Excel /
LibreOffice / Google Sheets.

The three `.bas` files in this directory are **optional add-ons** for teams that
want an `.xlsm` variant with in-workbook buttons for:

| Macro              | Button label         | What it does |
|--------------------|----------------------|--------------|
| `SaveAsCsv`        | *Save as CSV*        | Writes a UTF-8 CSV next to the workbook — plain RFC-4180 escaping, no `=` armour, BOM prefix. |
| `ValidateAll`      | *Validate Data*      | Two-phase check. Phase 1 (local): required fields, billTo enum, HS shape, ISO-2 country codes, integer qty, international-item rule. Phase 2 (network): POSTs current rows to `/api/v1/orders/import/validate` and merges backend errors + warnings. Colour-codes red / yellow / green. |
| `ValidateAddresses`| *Validate Addresses* | POSTs current rows to `/api/v1/orders/import/validate-addresses`; backend calls each row's picked carrier's address-validation API. Paints recipient / address cells yellow when the carrier flags the address. |

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
4. In the VBA editor: **File → Import File** → pick `SaveAsCsv.bas` → repeat
   for `ValidateAll.bas`. Each becomes its own Module in the workbook's
   VBAProject.
5. Back on the Import sheet: **Developer → Insert → Button (Form Control)**
   → draw button → assign macro `SaveAsCsv` → label it *Save as CSV*.
   Repeat for `ValidateAll`.
6. **Save** the workbook (still as `.xlsm`).
7. Optional backend follow-up: teach `OrderImportServiceImpl.xlsxTemplate()`
   to prefer the resource `.xlsm` when it exists (two-line change) so
   `?accountId=` requests still work by patching the account cells at
   serve time. Alternatively serve the static `.xlsm` unchanged and
   accept that account scoping only works for `.xlsx` downloads.

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
