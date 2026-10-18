"""R6+1 pull-forward on 8 Deferred bugs that are already on HEAD.

The 2026-10-14 R6 tracker marked 23 bugs 'Deferred to next round'.
Today's sweep confirmed 8 of them have in fact already landed on HEAD
via earlier dev commits — the tracker's Dev-status just wasn't flipped.
Plus 2 bugs got net-new code today (BUG-092 / BUG-093 via 3508b210).

Flip Dev-status Fixed on all 10, Fixed-in-build=2026-10-15.

Already-on-HEAD (dev note stale):
  BUG-068 — NotFound page already landed in AppRoutes.tsx
  BUG-072 — FedEx-to-Canada FTR+AES guard already present in NewShipmentPage.tsx
  BUG-073 — ValidationQuote already strips 'No price:' and HTTP 5xx tail
  BUG-081 — WarehouseEditorModal already branches on credsMissing in the status pill
  BUG-105 — ClientCustomsProfileServiceImpl.matchesSearch already appends country name
  BUG-107 — LabelTemplateEditorPage already has the magic-byte check
  BUG-127 — AuditLogPage already labels the two exports distinctly
  BUG-147 — SystemChannelSection already clears localStorage on 404

Net-new today:
  BUG-092 — ShippingConfigService.savePreset refuses tareWeight > maxWeight
  BUG-093 — ClientCodeMapServiceImpl.upsertShipvia surfaces "already existed"
            on in-place update
"""
import openpyxl

PATH = "docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx"
TODAY = "2026-10-15"

already_on_head = {
    "BUG-068": "Already on HEAD — in-shell NotFoundPage mounted at Route path='*' preserves the attempted URL.",
    "BUG-072": "Already on HEAD — NewShipmentPage requires FTR exemption or AES ITN for US → CA shipments.",
    "BUG-073": "Already on HEAD — ValidationQuote strips 'No price:' prefix and HTTP 5xx tail from quote.message.",
    "BUG-081": "Already on HEAD — WarehouseEditorModal branches on credsMissing so the status reads 'Carrier credentials not configured'.",
    "BUG-105": "Already on HEAD — ClientCustomsProfileServiceImpl.matchesSearch appends full country name to the needle so 'canada' / 'mexico' etc. hit.",
    "BUG-107": "Already on HEAD — LabelTemplateEditorPage.chooseLogo verifies PNG/JPEG magic bytes after the MIME check.",
    "BUG-127": "Already on HEAD — AuditLogPage labels the second button 'Export all (CSV)' (full set) vs the AdvancedDataTable 'Export' (current page).",
}

net_new_today = {
    "BUG-092": ("3508b210", "ShippingConfigService.savePreset now refuses tareWeight > maxWeight at 422."),
    "BUG-093": ("3508b210", "upsertShipvia reports 'already existed — the existing mapping was updated' when the (clientCode, erpCode, dest*) row was pre-existing."),
}

wb = openpyxl.load_workbook(PATH)
ws = wb["Bugs"]
hdr = {c.value: i + 1 for i, c in enumerate(ws[1])}

updated = []
for row in ws.iter_rows(min_row=2):
    bid = row[0].value
    if bid in already_on_head:
        prior = ws.cell(row=row[0].row, column=hdr["Developer comments"]).value or ""
        add = f"R6+1 ({TODAY}): {already_on_head[bid]}"
        combined = f"{prior}\n{add}" if prior and add not in prior else add
        ws.cell(row=row[0].row, column=hdr["Dev status"]).value = "Fixed"
        ws.cell(row=row[0].row, column=hdr["Developer comments"]).value = combined
        ws.cell(row=row[0].row, column=hdr["Fixed in build"]).value = TODAY
        updated.append(bid)
    elif bid in net_new_today:
        sha, note = net_new_today[bid]
        prior = ws.cell(row=row[0].row, column=hdr["Developer comments"]).value or ""
        add = f"R6+1 ({sha}, {TODAY}): {note}"
        combined = f"{prior}\n{add}" if prior and add not in prior else add
        ws.cell(row=row[0].row, column=hdr["Dev status"]).value = "Fixed"
        ws.cell(row=row[0].row, column=hdr["Developer comments"]).value = combined
        ws.cell(row=row[0].row, column=hdr["Fixed in build"]).value = TODAY
        updated.append(bid)

wb.save(PATH)
print(f"Updated {len(updated)}: {', '.join(updated)}")
