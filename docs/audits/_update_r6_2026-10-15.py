"""Update R6 tracker with the 2026-10-15 session commits.

Backend bundle (afeb4111): BUG-022, -061, -089, -118, -121
Frontend bundle (e17bf946): BUG-046, -061, -075, -087, -110, -119, -125, -134

A few of these were already marked Fixed in prior sessions (dev's own
R6 pass); this script extends their Developer comments with the new
commit sha so retest can target the right build.
"""
import openpyxl

PATH = "docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx"
TODAY = "2026-10-15"
BE_SHA = "afeb4111"
FE_SHA = "e17bf946"

shipped = {
    "BUG-022": (f"{BE_SHA}+{FE_SHA}",
        "Spread ESCAPE '\\' + escapeLike() to WarehouseRepository, "
        "AdminUserService (Criteria API) and AuditLogRepositoryCustomImpl. "
        "Delivery-log fix (a601e4aa) now matches every other search path."),
    "BUG-046": (FE_SHA,
        "_countryData.ts sources from utils/countries.ts; CountrySelect "
        "now exposes the full 250-country list. Address book dialog "
        "switched to CountrySelect so warehouse / client / address book agree."),
    "BUG-061": (f"{BE_SHA}+{FE_SHA}",
        "Scrubbed internal copy on SystemSettings specs (backend), "
        "workspaceRoutes descriptions, Carrier Accounts, Code Maps, "
        "Cutoffs & Holidays, External Systems, SystemSettingsPage "
        "deployment banner, DTC Scheduler 'Last changed by V<n>', "
        "Carrier Limits resolver blurb."),
    "BUG-075": (FE_SHA,
        "CustomFieldsPage.del() swapped window.confirm for "
        "notify.confirm (title + danger + confirmLabel). Matches "
        "Importer/Broker in-app pattern."),
    "BUG-087": (FE_SHA,
        "CarrierShippingLimitsPage table now uses border-separate + "
        "border-spacing-0 so Chrome's position:sticky on <th>/<td> "
        "actually sticks Actions at narrow viewports."),
    "BUG-089": (f"V161 {BE_SHA}",
        "V161 re-applies V30's branded_packaging_allowed=false for "
        "UPS/FEDEX/USPS ground services. V30 fired against empty "
        "shipping_service on fresh deploys and the UPS Ground row "
        "later synced in with the default TRUE. Now flipped."),
    "BUG-110": (FE_SHA,
        "Tenant→client wording on CustomFieldsSection, PrintersPage "
        "scanner enroll, InvoiceCopiesMatrix, LabelTemplateEditorPage, "
        "LabelTemplatesListPage, OutputDestinationsPage doc / dest "
        "type dropdowns (humanised codes)."),
    "BUG-118": (BE_SHA,
        "@JdbcTypeCode(SqlTypes.JSON) on ClientOutputDestination.config. "
        "Hibernate 6 was binding the String field as VARCHAR and "
        "Postgres rejected every save with 'column is of type jsonb "
        "but expression is of type character varying' → 500 on every "
        "destination create. Create now persists cleanly."),
    "BUG-119": (FE_SHA,
        "NotificationTemplatesPage 'Updated' line now routes through "
        "formatDateTime (treats Z-less backend timestamps as UTC)."),
    "BUG-121": (BE_SHA,
        "PUT /external-systems/{id}/tenant-routings/{tenantCode} "
        "now 400s on an unknown client code so a FE typo can't "
        "seed a new orphan after V158's retroactive cleanup."),
    "BUG-125": (FE_SHA,
        "CURRENCY_ALLOWLIST (15 currencies) in utils/money.ts drives "
        "Orders/New, Customs profile, Client Identity + Markup. MXN "
        "/ NZD / BRL etc. that were a client default but not pickable "
        "on the order form are now consistent across all three screens."),
    "BUG-134": (FE_SHA,
        "SKIPPED_INACTIVE added to the WritebackJournalStatus union "
        "+ badge map so the humanised cell doesn't rely on a hard cast."),
}

wb = openpyxl.load_workbook(PATH)
ws = wb["Bugs"]
hdr = {c.value: i + 1 for i, c in enumerate(ws[1])}

updated = []
for row in ws.iter_rows(min_row=2):
    bid = row[0].value
    if bid in shipped:
        sha, note = shipped[bid]
        ws.cell(row=row[0].row, column=hdr["Dev status"]).value = "Fixed"
        prior = ws.cell(row=row[0].row, column=hdr["Developer comments"]).value or ""
        new = f"R6+1 ({sha}, {TODAY}): {note}"
        # Preserve the prior round's comment on its own line so audit history
        # stays in one cell.
        combined = f"{prior}\n{new}" if prior and prior not in new else new
        ws.cell(row=row[0].row, column=hdr["Developer comments"]).value = combined
        ws.cell(row=row[0].row, column=hdr["Fixed in build"]).value = TODAY
        updated.append(bid)

wb.save(PATH)
print(f"Updated {len(updated)}: {', '.join(updated)}")
