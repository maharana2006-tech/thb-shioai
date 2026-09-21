import type {
  ShipmentValidationCheckStatus,
  ShipmentValidationIssue,
  ShipmentValidationResult,
} from '../api/shipmentValidationService'

/**
 * Sorting a Validate result into the areas of the shipment-check
 * checklist (ValidationChecklist).
 */
export type CheckGroupKey = 'addresses' | 'package' | 'service' | 'customs' | 'carrier'

export interface CheckGroup {
  key: CheckGroupKey
  title: string
  /** Page section the "Go to" link scrolls to. */
  sectionId: string
  errors: string[]
  warnings: string[]
  skipped: string[]
  /** Technical detail behind the row's messages (carrier replies), shown on request. */
  details: string[]
  status: 'fail' | 'warn' | 'pass' | 'skipped'
}

const GROUPS: { key: CheckGroupKey; title: string; sectionId: string }[] = [
  { key: 'addresses', title: 'Addresses', sectionId: 'sec-addresses' },
  { key: 'package', title: 'Package & weight', sectionId: 'sec-packages' },
  { key: 'service', title: 'Account, service & price', sectionId: 'sec-service' },
  { key: 'customs', title: 'Customs & dangerous goods', sectionId: 'sec-customs' },
  { key: 'carrier', title: 'Carrier check', sectionId: 'sec-service' },
]

/** Which area an issue belongs to — by the form field it names, else its code. */
export function groupOfIssue(issue: ShipmentValidationIssue): CheckGroupKey {
  const field = (issue.field ?? '').toLowerCase()
  const code = (issue.code ?? '').toLowerCase()
  if (field === 'carrier') return 'carrier'
  if (field.startsWith('recipient') || field.startsWith('sender') || field.startsWith('warehouse')) return 'addresses'
  if (field.startsWith('items') || field.startsWith('importer') || ['incoterms', 'currency', 'dutiesaccount', 'reasonforexport'].includes(field)
    || code.startsWith('customs') || code.startsWith('dg.') || code.startsWith('commodities')) return 'customs'
  if (field.startsWith('package') || field === 'weight' || ['length', 'width', 'height'].includes(field)) return 'package'
  return 'service'
}

const SKIPPED_GROUP: Record<string, CheckGroupKey> = {
  ship_to_allowlist: 'addresses',
  packaging_compatibility: 'package',
  package_limits: 'package',
  markup: 'service',
  customs: 'customs',
  dangerous_goods: 'customs',
  carrier_validate_shipment: 'carrier',
}

const SKIPPED_LABEL: Record<string, string> = {
  ship_to_allowlist: 'Client ship-to countries',
  packaging_compatibility: 'Package fits the service',
  package_limits: 'Package limits',
  markup: 'Client markup',
  customs: 'Customs',
  dangerous_goods: 'Dangerous goods',
  carrier_validate_shipment: 'Carrier check',
}

const statusOf = (g: Pick<CheckGroup, 'errors' | 'warnings' | 'skipped'>, ran: boolean): CheckGroup['status'] =>
  g.errors.length ? 'fail' : g.warnings.length ? 'warn' : ran ? 'pass' : 'skipped'

/** Sort a Validate result into the five areas of the checklist. */
export function buildCheckGroups(result: ShipmentValidationResult): CheckGroup[] {
  const groups = new Map<CheckGroupKey, CheckGroup>(GROUPS.map((g) => [g.key, { ...g, errors: [], warnings: [], skipped: [], details: [], status: 'pass' }]))
  for (const e of result.localErrors ?? []) groups.get(groupOfIssue(e))!.errors.push(e.message)
  for (const w of result.localWarnings ?? []) groups.get(groupOfIssue(w))!.warnings.push(w.message)
  for (const s of (result.skipped ?? []) as ShipmentValidationCheckStatus[]) {
    const key = SKIPPED_GROUP[s.name] ?? 'service'
    groups.get(key)!.skipped.push(`${SKIPPED_LABEL[s.name] ?? s.name}: ${s.reason}`)
  }
  const carrier = groups.get('carrier')!
  const c = result.carrier
  if (c) {
    // The carrier's failure is already one local error (field "carrier"): one
    // problem, one line. Its raw reply goes under "Details".
    if (!c.valid && c.matchLevel === 'NOT_SUPPORTED') carrier.skipped.push(c.message)
    else if (!c.valid && !carrier.errors.length) carrier.errors.push(c.message)
    carrier.details.push(...(c.errors ?? []).filter((e) => !carrier.errors.includes(e)))
    carrier.warnings.push(...(c.warnings ?? []))
  }
  const customsRan = result.international
  return GROUPS.map(({ key }) => {
    const g = groups.get(key)!
    const ran = key === 'carrier' ? !!c && c.matchLevel !== 'NOT_SUPPORTED'
      : key === 'customs' ? customsRan || g.errors.length > 0 || g.warnings.length > 0
        : true
    return { ...g, status: statusOf(g, ran) }
  })
}

/** The panel's headline, counted from the rows so the numbers always agree. */
export function checklistHeadline(groups: CheckGroup[]): string {
  const fix = groups.reduce((n, g) => n + g.errors.length, 0)
  const review = groups.reduce((n, g) => n + g.warnings.length, 0)
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
  if (fix > 0) {
    return `${plural(fix, 'issue', 'issues')} to fix before the label can be bought`
      + (review > 0 ? ` · ${review} to review` : '')
  }
  if (review > 0) return `Ready to ship — ${plural(review, 'thing', 'things')} to review`
  return 'All checks passed — ready to generate the label'
}
