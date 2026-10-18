"""R6+1 — BUG-128 audit-write wiring (sprint 1 of ~2).

f46b6f01 wires AuditService.record into 7 of the Settings services the
R6 tester called out as missing. Entity types LABEL_TEMPLATE, CODE_MAP,
CUSTOMS_PROFILE, CLIENT_POLICY (custom-field / scanner / mail-provider),
CLIENT_MARKUP and ROUTING_RULE will now return non-zero rows on the
Logs > Entity type filter.

Status stays Deferred — tester's list also includes regular Printers
CRUD, Cutoffs/Holidays, Address book (recipients), Pickups and Output
Destinations. Follow-up sprint planned.
"""
import openpyxl

PATH = "docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx"
TODAY = "2026-10-15"

wb = openpyxl.load_workbook(PATH)
ws = wb["Bugs"]
hdr = {c.value: i + 1 for i, c in enumerate(ws[1])}

for row in ws.iter_rows(min_row=2):
    if row[0].value == "BUG-128":
        prior = ws.cell(row=row[0].row, column=hdr["Developer comments"]).value or ""
        add = (f"R6+1 sprint 1 (f46b6f01, {TODAY}): AuditService wired into "
               "LabelTemplateServiceImpl, ClientCodeMapServiceImpl, "
               "ClientCustomsProfileServiceImpl, CustomFieldServiceImpl, "
               "ClientBillingMarkupServiceImpl, RoutingRuleServiceImpl, "
               "PrinterScanService + MailConfigService. 56/56 related "
               "unit tests green. Dev status stays Deferred because the "
               "second half (Printers CRUD / Cutoffs / Address book / "
               "Pickups / Output Destinations) is batched for sprint 2.")
        combined = f"{prior}\n{add}" if prior and add not in prior else add
        ws.cell(row=row[0].row, column=hdr["Developer comments"]).value = combined
        print("Updated BUG-128.")
        break

wb.save(PATH)
