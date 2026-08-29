import type { ServicePackageLink } from '../api/shippingConfigService'

/**
 * Sprint 52 PR 2 — client-side mirror of the backend
 * PackagingCompatibilityGuard (Sprint 52 PR 1). Given the raw
 * {@link ServicePackageLink} rows returned by
 * {@code shippingConfigService.catalog()}, returns the set of preset IDs
 * compatible with the picked service — or {@code null} to indicate "no
 * filter" (service not picked yet, or service has zero linked rows so
 * the backend guard will fall through to
 * {@code SERVICE_HAS_NO_LINKED_PACKAGES} anyway).
 *
 * <p>Null-return semantics on empty pool are deliberate: rather than hide
 * every CARRIER preset in the dropdown (which would look broken to the
 * operator), we surface the same choices they used to see and let the
 * server return the specific "config incomplete" error with the fix path
 * to /settings/shipping-catalog.
 *
 * <p>CUSTOM presets are NOT filtered here — the packaging dropdown puts
 * them in a separate optgroup ("Your boxes") that stays visible always.
 * The backend guard treats them as implicit-allowed via the kind=CUSTOM
 * short-circuit.
 */
export function compatiblePresetIds(
  links: readonly ServicePackageLink[] | null | undefined,
  serviceId: number | '' | null | undefined,
): Set<number> | null {
  if (serviceId === '' || serviceId == null) return null
  const forService = (links ?? [])
    .filter((l) => l.serviceId === serviceId)
    .map((l) => l.presetId)
  return forService.length ? new Set(forService) : null
}
