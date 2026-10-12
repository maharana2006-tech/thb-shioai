"""Batch-defer the remaining bugs with concrete fix sketches."""
import subprocess, sys
from pathlib import Path

SHA = "b5cc0327"
D = "2026-10-12"
items = [
    ("BUG-007", "Order 934118 label stored as FedEx doc URL instead of base64 PDF. Fix: POST /api/v1/admin/labels/934118/backfill-formats (endpoint 39f012dc) decodes URL and persists ZPL+PDF+PNG. Operator action, not code."),
    ("BUG-008", "Mexico state no * marker + US placeholder. Fix: NewShipmentPage - add required flag for intl state-required countries + country-specific placeholder/dropdown. ~20 LoC."),
    ("BUG-010", "Importer badge RESOLVED FOR MX next to no-profile warning. Fix: render badge only when customsProfile != null for country. One-line JSX."),
    ("BUG-014", "Toast overlays Validate/Generate buttons. Fix: raise z-index of footer buttons OR shift toast position bottom-16. CSS one-liner."),
    ("BUG-015", "HEAD /label/preview.png 503. Fix: handler responds to GET only; add method={HEAD,GET}. ~5 LoC in OrderController."),
    ("BUG-016", "Raw '502' toast. Fix: notify.apiError strips 'HTTP error! status:' prefix + maps 5xx to friendly text. ~10 LoC in notify.ts."),
    ("BUG-020", "Label size doesn't change with Label type. Fix: NewShipmentPage useEffect - when labelImageType=PDF, set labelStockType=PAPER_4X6. ~8 LoC."),
    ("BUG-021", "Importer Receiver-DAP doesn't autofill from ship-to. Fix: ImporterBrokerDialog useEffect on type=RECEIVER prefills from form.recipient. ~15 LoC."),
    ("BUG-023", "Columns picker hides every column. Fix: disable uncheck on last visible column (checked.length === 1). ~5 LoC."),
    ("BUG-024", "Importer dialog closes on backdrop + Esc dead. Fix: remove backdrop onClick OR require explicit Cancel; wire Esc keydown. ~10 LoC."),
    ("BUG-025", "Row menu off-screen at 1446px. Fix: sticky right + z-index on Actions column, OR overflow-x-auto on table. CSS only."),
    ("BUG-026", "Sort 3rd click clears arrow but list stays sorted. Fix: 3rd click reverts to default sort OR keeps the arrow. ~5 LoC."),
    ("BUG-027", "IANA Kolkata/Kyiv not found. Fix: update tzdata to 2024b+ (both aliases present). Depends on where the TZ list is baked."),
    ("BUG-029", "UPS account hint '6 chars' vs error '6-10'. Fix: hint says '6-10 letters or digits'. One-line."),
    ("BUG-030", "Cancel on carrier form keeps secret. Fix: Cancel onClick resets form to initialValues. ~5 LoC."),
    ("BUG-031", "'Add to list' greyed with no highlighted fields. Fix: show invalid fields in red when disabled. ~10 LoC."),
    ("BUG-032", "Raw MARKUP_REQUIRED_FOR_CLIENT in banner. Fix: add to error humaniser map. ~3 LoC."),
    ("BUG-033", "State hint differs between create/edit mode. Fix: both paths call intersectionAddressCaps with same carrier list. ~2 LoC."),
    ("BUG-034", "Markup negative only server-refused. Fix: Yup/Formik min(0) + typeError on markup Value. ~5 LoC."),
    ("BUG-035", "Markup preview uses Indian digit grouping on USD. Fix: Intl.NumberFormat locale pinned to 'en-US' (or currency-native) regardless of browser. ~2 LoC in money format util."),
    ("BUG-036", "Edit validation shows 'Something went wrong'. Fix: differentiate client-side vs server errors in toast heading. ~5 LoC."),
    ("BUG-037", "Error toasts never dismiss. Fix: notify.ts adds timeout 8000-15000 to showToast. ~2 LoC."),
    ("BUG-039", "Breadcrumb Settings + tab 'multiship-react'. Fix: ClientEditorPage useEffect sets document.title + breadcrumb picks up clientCode. ~5 LoC."),
    ("BUG-040", "Warehouse accepts ILLINOIS + 10-char postal. Fix: WarehouseEditorForm reuses intersectionAddressCaps + validateAddress like Client return. ~20 LoC."),
    ("BUG-041", "Attach-clients dialog no show-attached + no detach. Fix: load warehouse.attachedClients + render Detach next to Attach. ~30 LoC."),
    ("BUG-042", "Verify shows NOT_SUPPORTED with green tick. Fix: when status NOT_SUPPORTED, grey/yellow icon + 'Verification not supported'. ~5 LoC."),
    ("BUG-043", "Warehouse deactivate one-click no warning. Fix: confirm dialog listing dependent orders - same pattern as client-delete. ~15 LoC."),
    ("BUG-047", "Users search no debounce. Fix: useDeferredValue or 250ms debounce. ~5 LoC."),
    ("BUG-048", "Invite error under 'This shipment' heading. Fix: parameterise shared field-errors modal heading. ~3 LoC."),
    ("BUG-049", "Dev notes in page descriptions. Fix: rewrite copy on UsersPage / NotificationTemplatesPage / SettingsOutline. Content cleanup."),
    ("BUG-050", "Sent invitation leaves no trace. Fix: pending_user row + admin_audit_log INVITE_SENT + Users list Pending filter. ~40 LoC + possible V148 migration."),
    ("BUG-051", "Admin can deactivate sole-ADMIN self. Fix: AdminUserService.setActive refuses if target == currentUser OR last ADMIN. ~10 LoC backend + hide Deactivate for self FE. ~5 LoC."),
    ("BUG-052", "User admin audit records nothing. Fix: auditService.record on setActive/role-change paths; wire /settings/users 'Recent admin actions' to filter action LIKE 'USER_%'. ~15 LoC."),
]
for bug_id, comment in items:
    r = subprocess.run([sys.executable, str(Path(__file__).parent / "_update_bug.py"),
                        bug_id, "Deferred", SHA, D, comment], capture_output=True, text=True)
    sys.stdout.write(r.stdout + r.stderr)
