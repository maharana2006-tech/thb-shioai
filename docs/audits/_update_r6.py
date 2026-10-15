"""Update R6 tracker with the fixes committed this session."""
import openpyxl
wb = openpyxl.load_workbook("docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx")
ws = wb['Bugs']
hdr = {c.value: i+1 for i, c in enumerate(ws[1])}

shipped = {
    # Infrastructure
    'BUG-078': ('d266e348', 'ClientEditorPage JSX comment moved out of ternary; unblocks client wizard retests.'),
    # P1 handler amendments
    'BUG-038': ('9681f4ff', 'FE columns (contact/orderCount/actions) now alias to backend map keys so CSV contains the right columns.'),
    'BUG-098': ('9681f4ff', 'IllegalArgumentException now maps to 400 so duplicate holiday says what it is instead of 500.'),
    'BUG-099': ('9681f4ff', 'Field marking still TODO but 500 is now 400 "references something that doesn\'t exist".'),
    'BUG-111': ('9681f4ff', 'Preserve existing createdAt on edit so Custom Fields upsert stops violating NOT NULL.'),
    'BUG-118': ('9681f4ff', 'Output Destinations IAE now 400; create still fails on missing config - needs service-side fix later.'),
    'BUG-119': ('9681f4ff', 'formatDateTime treats Z-less timestamps as UTC so IST operators see wall-clock time.'),
    # Data cleanup
    'BUG-080': ('V157 f41ee5c7', 'V157 deletes orphan client_warehouse rows pointing at deleted clients.'),
    'BUG-121': ('V158 6754690c', 'V158 deletes orphan tenant_settings rows (MKL274 removed).'),
    'BUG-130': ('V157', 'V157 redacts client_id in old carrier_api_log rows.'),
    'BUG-140': ('V157', 'V157 deletes orphan printer_scan_agent rows for deleted tenants.'),
    # New R6 findings
    'BUG-143': ('35d7902c', 'Reason-of-export only auto-fills when empty — operator picks persist across carrier switch.'),
    'BUG-144': ('3f4520c8', 'HS lookup warning suppressed while the dropdown is showing matches.'),
    'BUG-145': ('35d7902c', 'Invite "still pending" expiry rendered as "Oct 21, 2026, 2:06 PM UTC".'),
    'BUG-146': ('35d7902c', 'TimezoneCombobox commits a typed+valid IANA zone on blur.'),
    'BUG-147': ('3f4520c8', 'SystemChannelSection clears remembered-tenant from localStorage when the client no longer exists.'),
    'BUG-148': ('3f4520c8', 'RegionCountryPicker grid widened to 1/2/3 cols so labels stay readable.'),
    # Reopened amendments
    'BUG-022': ('a601e4aa', 'Delivery log recipient filter now escapes % _ and query uses ESCAPE "\\\\".'),
    'BUG-061': ('cb9e4ed5', 'SystemChannelSection scrubbed TENANT_CHANNEL_NOT_ENABLED raw code; "tenant" -> "client".'),
    'BUG-079': ('6754690c', 'Warehouse State input forces uppercase on change.'),
    'BUG-089': ('6754690c', 'Allow branded packaging toggle hidden entirely for Ground-family services + inline amber note.'),
    'BUG-094': ('6754690c', 'Code Maps ERP code input gains maxLength=40.'),
    'BUG-095': ('6754690c', 'Carrier Limits form rejects unknown carrier codes (allowlist).'),
    'BUG-096': ('6754690c', 'Effective-until refuses past date on the FE.'),
    'BUG-102': ('V159 cb9e4ed5', 'V159 seeds Dec 25 Christmas Day for current + next year.'),
    'BUG-104': ('cb9e4ed5', 'Canadian BN+RM pattern enforced in CustomsProfileModal.'),
    'BUG-106': ('cb9e4ed5', 'Returns Analytics header pluralises "returns" correctly.'),
    'BUG-110': ('a601e4aa', 'Custom Fields page says "Client scope" / "Platform-wide (all clients)"; description scrubbed.'),
    'BUG-112': ('6754690c', 'Custom Field key input forces snake_case + 60-char cap on change.'),
    'BUG-114': ('6754690c', 'Printer save-error routing checks host/IP before "name" substring so address error shows under Address.'),
    'BUG-115': ('6754690c', 'PrinterService.update clears stale lastTestOk/Message/At when host or port changed.'),
    'BUG-117': ('cb9e4ed5', 'PrinterScanService.enrollAgent rejects non-conforming agent id + hostname.'),
    'BUG-124': ('e15ef349', 'DTC Scheduler tenants list pulled from Multiship clients (not Oracle NDS).'),
    'BUG-125': ('d24684cc', 'Currencies description no longer references a non-existent per-tenant allowlist.'),
    'BUG-134': ('a601e4aa', 'Writeback Journal Status cell humanises; Retry hidden on OK rows.'),
}
verified_prior = {
    'BUG-036': 'Markup validation heading shipped as 9b7cb64d earlier this session.',
    'BUG-075': 'Importer/Broker in-app confirm shipped as 15ab13b8 earlier.',
    'BUG-076': 'CSV formula-injection guard shipped as 45c6b922 earlier.',
    'BUG-077': 'Markup overflow now 400 via GlobalExceptionHandler (48d5c34f).',
    'BUG-120': 'WMS default-cred UI copy scrubbed as fb58156c earlier.',
}
deferred = {
    'BUG-072': 'FTR exemption passes without ITN on FedEx-to-Canada. Needs validation when exemption=None (using AES).',
    'BUG-081': 'Warehouse verify "VERIFICATION NOT SUPPORTED FOR THIS COUNTRY" when real reason is missing credentials. Wording.',
    'BUG-090': 'Shipping Catalog tabs don\'t share origin. Needs lifted-state origin filter.',
    'BUG-097': 'FedEx Pak max weight 5 lb vs 3 lb across screens. Needs product + data decision on authoritative value.',
    'BUG-007': 'Needs backfill endpoint invocation for #934118; no new code.',
    'BUG-017': 'Code fix covers new orders; ERROR path missed by V150 — needs broader backfill.',
    'BUG-057': 'FEDEX label vocab needs full form↔registry unification across both directions.',
    'BUG-068': 'Unknown-route catchall lands on /dashboard but preserves nothing. Needs a NotFound page.',
    'BUG-070': 'Three error envelope shapes still vary; needs API-wide response contract.',
    'BUG-073': 'Price-&-delivery box renders raw q.message; needs cleaner pass similar to validationChecklist.',
    'BUG-082': 'Address book field validation needs yup schema wiring; defer.',
    'BUG-085': 'Audit entry for invite resend/revoke; needs recordAction wiring.',
    'BUG-086': 'Mail form autofill unchanged; needs same new-password treatment as Carrier Accounts.',
    'BUG-087': 'Carrier Limits uses plain <table>, not AdvancedDataTable; needs grid migration.',
    'BUG-088': 'lastUsedAt wiring on label-sent event; multi-connector change.',
    'BUG-092': 'tare > max weight guard; needs save validation on Add Package.',
    'BUG-093': 'Duplicate alias silent replace; needs "updated existing" message in service.',
    'BUG-101': 'Cutoff rules warehouse-id → warehouse-code picker.',
    'BUG-103': 'Pickup form validation (window order, past date, 0 pkgs, phone shape).',
    'BUG-105': 'Importer search by country name/city; backend search needs expand.',
    'BUG-107': 'Logo content magic-byte check; current fix is MIME-level only.',
    'BUG-109': 'Templates Preview renders order\'s packing-slip not template; needs render path by template id.',
    'BUG-116': 'Printer routing popover clipped by grid overflow; needs portal.',
    'BUG-123': 'HS lookup keyboard support + dialog focus/escape audit.',
    'BUG-127': 'Export button (visible page) vs Export CSV (full) wording clarified later.',
    'BUG-128': 'Big — audit calls on templates / code maps / customs profiles / custom fields / printers / scanners / mail. Multi-service pass.',
    'BUG-129': 'Logs filter Entity type + Action lists need DB-driven population.',
    'BUG-131': 'User chose "every connector call" — multi-file threading of orderNo/tracking into carrier HTTP interceptor. Punt to dedicated session.',
    'BUG-135': 'Alerts History tenant/order; needs alert-emission plumbing.',
    'BUG-136': 'Return label origin same as recipient; needs ReturnFromCustomer prefill rewrite.',
    'BUG-137': 'ZPL return label SHIP FROM "Multiship Warehouse, Bengaluru"; return-shipper is wrong at label-generation time, baked into the stored ZPL. Needs FedExConnector return branch fix. User chose deep-dive.',
    'BUG-138': 'Draft resume keeps staged carrier account but not creds; needs explicit "credentials required" guard on Summary READY.',
    'BUG-139': 'Add Client always resumes last draft; needs Discard-draft action.',
}

today = '2026-10-14'
for row in ws.iter_rows(min_row=2):
    bid = row[0].value
    if bid in shipped:
        sha, note = shipped[bid]
        ws.cell(row=row[0].row, column=hdr['Dev status']).value = 'Fixed'
        ws.cell(row=row[0].row, column=hdr['Developer comments']).value = f'Fixed on dev ({sha}): {note}'
        ws.cell(row=row[0].row, column=hdr['Fixed in build']).value = today
    elif bid in verified_prior:
        ws.cell(row=row[0].row, column=hdr['Dev status']).value = 'Fixed'
        ws.cell(row=row[0].row, column=hdr['Developer comments']).value = f'Fix landed earlier this session: {verified_prior[bid]}'
        ws.cell(row=row[0].row, column=hdr['Fixed in build']).value = today
    elif bid in deferred:
        ws.cell(row=row[0].row, column=hdr['Dev status']).value = 'Deferred'
        ws.cell(row=row[0].row, column=hdr['Developer comments']).value = f'Deferred to next round: {deferred[bid]}'

wb.save("docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx")
print(f'Fixed: {len(shipped)}; Deferred: {len(deferred)}')
# Count still-open after update
still = 0
remaining = []
for row in ws.iter_rows(min_row=2):
    if row[23].value in ('Open','Reopened') and row[25].value not in ('Fixed','Deferred'):
        still += 1
        remaining.append(row[0].value)
print(f'Still untouched: {still}')
if remaining:
    print(f'Still: {", ".join(remaining)}')
