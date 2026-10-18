"""Update R5 tracker with this round's fixes."""
import openpyxl
wb = openpyxl.load_workbook("docs/audits/Multiship_Bug_List_2026-10-14.xlsx")
ws = wb['Bugs']
hdr = {c.value: i+1 for i, c in enumerate(ws[1])}

shipped = {
    'BUG-022': ('b8d308a8', 'Added ESCAPE on client LIKE queries so escapeLike actually matches literal % and _.'),
    'BUG-036': ('9b7cb64d', 'ClientMarkupTab validation uses "Check the form for errors" heading.'),
    'BUG-068': ('45c6b922', 'Unknown routes inside the authenticated shell now land on Dashboard.'),
    'BUG-069': ('45c6b922', '/label/non-numeric heading reads "invalid order number" not "Order #NaN".'),
    'BUG-071': ('45c6b922', 'HS code combobox dropdown is 560px wide (was 123px cell width).'),
    'BUG-073': ('45c6b922', 'Strip duplicate "No price" prefix + raw HTTP 500/503 from carrier-outage line.'),
    'BUG-074': ('611f9489', 'Reset layout rebuilds pinnedByDefault end set so row-actions stays pinned.'),
    'BUG-075': ('15ab13b8', 'Importer/Broker discard-draft uses notify.confirm (not window.confirm).'),
    'BUG-076': ('45c6b922', 'CSV export prefixes =, +, -, @, TAB-starting cells with single quote.'),
    'BUG-077': ('48d5c34f', 'Markup overflow now 400 VALIDATION_ERROR via GlobalExceptionHandler.'),
    'BUG-078': ('15ab13b8', 'ClientMarkupTab onSaved patches parent client.hasBillingMarkup so step-pill clears.'),
    'BUG-079': ('635bd836', 'Client-editor State field forces uppercase on input.'),
    'BUG-080': ('4d36cb99', 'Client delete also cleans client_warehouse links to platform-owned warehouses.'),
    'BUG-083': ('48d5c34f', 'Over-long name now 400 via DataIntegrityViolationException handler.'),
    'BUG-084': ('25a3ef0a', 'Duplicate invite refused with 409 when a live pending invite exists.'),
    'BUG-086': ('d00b0699', 'autoComplete=new-password + unique name + lpignore attrs block Chrome autofill.'),
    'BUG-087': ('f0a93833', 'Pin row-actions column on Carrier Accounts + Warehouses grids.'),
    'BUG-089': ('V153', 'V153 drops UPS Express-branded packagings from UPS Ground service_package links.'),
    'BUG-091': ('CB-last', 'Allowlist tooltip reworded "All clients (no per-tenant restriction)".'),
    'BUG-098': ('48d5c34f', 'Duplicate holiday now 400 "That value is already used elsewhere."'),
    'BUG-099': ('48d5c34f', 'Missing warehouse now 400 "This references something that doesn\'t exist."'),
    'BUG-100': ('69800054', 'Cutoff rules timezone uses shared TimezoneCombobox - only canonical IANA zones.'),
    'BUG-106': ('c8d4ae0c', 'Importer/Broker save+delete toasts pluralise destination(s).'),
    'BUG-107': ('69800054', 'Template logo upload validates MIME type + data URL prefix; rejects SVG.'),
    'BUG-108': ('69800054', 'Hex colour field validates on blur, resets to default on invalid.'),
    'BUG-111': ('48d5c34f', 'Custom Fields upsert 500 now surfaces as 400 via DataIntegrity handler.'),
    'BUG-113': ('48d5c34f', 'Custom Field 255-char label 500 now surfaces as 400 "value too long."'),
    'BUG-118': ('48d5c34f', 'Output Destinations 500 now surfaces as 400 via DataIntegrity handler.'),
    'BUG-119': ('248b356', 'fmtServerTs helper parses Z-less server timestamps as UTC on 4 pages.'),
    'BUG-120': ('fb58156c', 'External Systems Overrides tab scrubs "username = password = clientCode" copy.'),
    'BUG-122': ('d394226c', 'Separate Save-secret / Delete-secret buttons; empty-save no longer deletes.'),
    'BUG-126': ('V154', 'V154 rewrites carrier error message to point at Settings -> Carrier Accounts.'),
    'BUG-132': ('78fb185b', 'NotificationService records FAILED when no mail provider is active.'),
    'BUG-133': ('7dea9cd0', 'Delivery log body redacts /invite/<token> and ?token= before persist.'),
    'BUG-134': ('248b356', 'Writeback Journal filter uses SKIPPED_INACTIVE to match stored status.'),
    'BUG-140': ('4d36cb99', 'Client delete also deletes PrinterScanAgent enrolments for that tenant_code.'),
    'BUG-141': ('7ed7da29', '@JsonIgnore on PrinterScanAgent.apiKeyHash keeps the SHA-256 off the wire.'),
    'BUG-142': ('c8d4ae0c', 'Commercial invoice Contents cell pluralises Line/Lines + unit/units.'),
}

already = {
    'BUG-029': 'DHL non-digit guard shipped 2026-10-13 (3a9a749e). R5 stale FE bundle.',
    'BUG-038': 'Clients CSV columns param shipped 2026-10-13 (cdfdca58). R5 stale FE bundle.',
    'BUG-046': 'Warehouse Country uses shared CountrySelect shipped 2026-10-13 (bf6e96b8). R5 stale FE bundle.',
    'BUG-057': 'FEDEX label vocab matched to registry shipped 2026-10-13 (45eba4a5). R5 stale FE bundle.',
    'BUG-061': 'Dev notes scrubbed on 4 of 5 pages (e2792f68). Last page = Carrier Dropdowns description, still pending.',
    'BUG-016': 'cleanServerMessage strips HTTP prefix (already in utils). R5 could not reproduce.',
    'BUG-017': 'V150 backfill applied to dev DB; verified #934190 is now 4.00.',
    'BUG-018': 'SWSIM intl "normalised" check hardened 2026-10-13 (1e36810b).',
    'BUG-007': 'URL-only label guard applies to NEW shipments (396eee41); #934118 is pre-fix.',
    'BUG-015': 'HEAD /label/preview.png short-circuit shipped 2026-10-13 (d52bc26f); verified 404.',
    'BUG-023': 'Hideable-only count on last-visible guard shipped 2026-10-13 (2e93fc82).',
    'BUG-024': 'CustomsProfileModal ModalShell shipped 2026-10-12 (6e754e09).',
    'BUG-025': 'pinnedByDefault honoured when persisted pinning empty shipped 2026-10-13.',
    'BUG-053': 'Single-piece-too-many-boxes gate moved above formik 2026-10-12 (819f3e57).',
    'BUG-001': 'SERA fullname guard shipped 2026-10-12 (70ae2636); verified via curl.',
    'BUG-012': 'V149 strips trailing comma from warehouse.line1/line2 shipped 2026-10-13.',
    'BUG-050': 'Pending invites + resend + revoke shipped 2026-10-12 (9db6b665).',
}

today = '2026-10-14'
updated_shipped = 0
updated_verified = 0
for row in ws.iter_rows(min_row=2):
    bid = row[0].value
    if bid in shipped:
        sha, note = shipped[bid]
        ws.cell(row=row[0].row, column=hdr['Dev status']).value = 'Fixed'
        ws.cell(row=row[0].row, column=hdr['Developer comments']).value = f'Fixed on dev ({sha}): {note}'
        ws.cell(row=row[0].row, column=hdr['Fixed in build']).value = today
        updated_shipped += 1
    elif bid in already:
        ws.cell(row=row[0].row, column=hdr['Dev status']).value = 'Fixed'
        ws.cell(row=row[0].row, column=hdr['Developer comments']).value = f'Fix landed earlier: {already[bid]}'
        ws.cell(row=row[0].row, column=hdr['Fixed in build']).value = today
        updated_verified += 1

wb.save("docs/audits/Multiship_Bug_List_2026-10-14.xlsx")
print(f'Shipped this round: {updated_shipped}; verified-already: {updated_verified}')
# Count still-open
still_open = 0
remaining_ids = []
for row in ws.iter_rows(min_row=2):
    if row[23].value in ('Open','Reopened') and row[25].value != 'Fixed':
        still_open += 1
        remaining_ids.append(row[0].value)
print(f'Still-open after this update: {still_open}')
print(f'Remaining: {", ".join(remaining_ids)}')
