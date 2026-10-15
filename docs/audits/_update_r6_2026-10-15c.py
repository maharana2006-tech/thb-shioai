"""Final R6+1 pass for 2026-10-15.

Records today's follow-up code commits + marks the ~34 bugs confirmed
on HEAD (per the verification sweep) with today's Fixed-in-build so the
R7 tester can target the right batch.

Code commits today:
  afeb4111  backend  — bug-022, -061, -089, -118, -121
  e17bf946  frontend — bug-046, -061, -075, -087, -110, -119, -125, -134
  8cb8b4ba  backend  — test fixes + latent SERA v3→v4 topup bug
  46d6db84  frontend — test fixes
  2207535b  mixed    — bug-079, -099, -130
  0b501583  frontend — bug-076 FE csv guard

Verified-on-HEAD bugs that just need an R7 retest:
  BUG-018, -029, -036, -038, -057, -077, -078, -080, -082, -094, -095,
  -096, -098, -101, -102, -103, -104, -106, -109, -111, -112, -114,
  -115, -116, -117, -120, -124, -135, -138, -139, -140, -143, -144,
  -145, -146, -147, -148
"""
import openpyxl

PATH = "docs/audits/Multiship_Bug_List_2026-10-14_R6.xlsx"
TODAY = "2026-10-15"

# New code work today — mark Fixed-in-build=TODAY and append note.
new_fixes = {
    "BUG-079": ("2207535b", "Warehouses grid upper-cases state at render too; legacy 'il' rows now display 'IL' without a resave."),
    "BUG-099": ("2207535b", "CutoffAdminController pre-validates warehouseId FK + throws IAE naming the field."),
    "BUG-130": ("V162 2207535b", "V162 masks client_id at START of carrier_api_log request_body; V157's regex only caught `?`/`&` prefixes, FedEx form bodies open with the field."),
    "BUG-076": ("0b501583", "FE csv.ts csvCell now prefixes '=/+/-/@/TAB' with a single quote (mirrors backend)."),
}

# Verified on HEAD per the explore agent sweep — Fixed-in-build=TODAY so
# R7 tester knows these are ready.
verified_on_head = [
    "BUG-018", "BUG-029", "BUG-036", "BUG-038", "BUG-057", "BUG-077",
    "BUG-078", "BUG-080", "BUG-082", "BUG-094", "BUG-095", "BUG-096",
    "BUG-098", "BUG-101", "BUG-102", "BUG-103", "BUG-104", "BUG-106",
    "BUG-109", "BUG-111", "BUG-112", "BUG-114", "BUG-115", "BUG-116",
    "BUG-117", "BUG-120", "BUG-124", "BUG-135", "BUG-138", "BUG-139",
    "BUG-140", "BUG-143", "BUG-144", "BUG-145", "BUG-146", "BUG-147",
    "BUG-148",
]

wb = openpyxl.load_workbook(PATH)
ws = wb["Bugs"]
hdr = {c.value: i + 1 for i, c in enumerate(ws[1])}

code_updated = []
head_updated = []
for row in ws.iter_rows(min_row=2):
    bid = row[0].value
    if bid in new_fixes:
        sha, note = new_fixes[bid]
        prior = ws.cell(row=row[0].row, column=hdr["Developer comments"]).value or ""
        new = f"R6+1 ({sha}, {TODAY}): {note}"
        combined = f"{prior}\n{new}" if prior and new not in prior else new
        ws.cell(row=row[0].row, column=hdr["Dev status"]).value = "Fixed"
        ws.cell(row=row[0].row, column=hdr["Developer comments"]).value = combined
        ws.cell(row=row[0].row, column=hdr["Fixed in build"]).value = TODAY
        code_updated.append(bid)
    elif bid in verified_on_head:
        ws.cell(row=row[0].row, column=hdr["Fixed in build"]).value = TODAY
        head_updated.append(bid)

wb.save(PATH)
print(f"Code-updated {len(code_updated)}: {', '.join(code_updated)}")
print(f"Marked verified-on-HEAD {len(head_updated)}: {', '.join(head_updated)}")
