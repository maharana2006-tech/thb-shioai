import type { ServicePackageLink } from '../api/shippingConfigService'

/**
 * Sprint 52 PR 2 — client-side mirror of the backend
 * PackagingCompatibilityGuard (Sprint 52 PR 1). Given the raw
 * {@link ServicePackageLink} rows returned by
 * {@code shippingConfigService.catalog()}, returns the set of preset IDs
 * compatible with the picked service — or {@code null} to indicate "no
 * service picked, don't filter".
 *
 * <p>MKL246 validation F6 fix (2026-09-27): a service with ZERO linked
 * rows now returns an empty Set, not null. That hides every CARRIER
 * preset (matching FedEx Priority's already-filtered behaviour) instead
 * of showing all of them and then failing at submit with
 * {@code SERVICE_HAS_NO_LINKED_PACKAGES}. UPS Ground was the canonical
 * broken case — its dropdown offered 6 carrier packagings that all
 * rejected at the carrier.
 *
 * <p>CUSTOM presets are NOT filtered here — the packaging dropdown puts
 * them in a separate optgroup ("Your boxes") that stays visible always.
 * The backend guard treats them as implicit-allowed via the kind=CUSTOM
 * short-circuit, so the operator still has a working path (custom
 * package) when a service has no linked CARRIER presets.
 */
export function compatiblePresetIds(
  links: readonly ServicePackageLink[] | null | undefined,
  serviceId: number | '' | null | undefined,
): Set<number> | null {
  if (serviceId === '' || serviceId == null) return null
  // Partial API response (links unavailable) — defensive "don't filter"
  // so we don't blank the dropdown on a transient fetch failure.
  if (links == null) return null
  const forService = links
    .filter((l) => l.serviceId === serviceId)
    .map((l) => l.presetId)
  return new Set(forService)
}
