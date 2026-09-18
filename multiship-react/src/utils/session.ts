/**
 * Sprint 51 FE-M2 — logout used to leave a trail of per-user cache behind
 * in localStorage: client-editor drafts, per-table layouts, and the last
 * connected-carrier snapshot. On a shared machine the next operator saw
 * the previous user's draft and table preferences.
 *
 * {@link clearAppStorage} wipes every namespace the SPA writes on its own
 * outside the auth session itself. Keeps the drop targeted (prefix match)
 * so localStorage entries owned by unrelated tools on the same origin are
 * left alone.
 */
const CLIENT_EDITOR_DRAFT_PREFIX = 'clientEditorDraft:'
const ADVANCED_DATA_TABLE_PREFIX = 'advanced-data-table:'
const CONNECTED_CARRIERS_KEY = 'multiship_connected_carriers'
// PR-Printer-P1.7 — the /settings/printers page remembers the last-
// picked LAN scan tenant. Sweep on logout so a shared machine doesn't
// hand the next admin a stale tenant scope on the printers page.
const SCAN_TENANT_KEY = 'multiship_scan_tenant'

export const clearAppStorage = (): void => {
  if (typeof window === 'undefined') return

  try {
    const keys: string[] = []
    for (let i = 0; i < window.localStorage.length; i++) {
      const key = window.localStorage.key(i)
      if (!key) continue
      if (
        key.startsWith(CLIENT_EDITOR_DRAFT_PREFIX) ||
        key.startsWith(ADVANCED_DATA_TABLE_PREFIX) ||
        key === CONNECTED_CARRIERS_KEY ||
        key === SCAN_TENANT_KEY
      ) {
        keys.push(key)
      }
    }
    // Second pass — removing during iteration shifts indices and misses keys.
    keys.forEach((key) => window.localStorage.removeItem(key))
  } catch {
    // storage quota / private-mode failures are non-fatal on logout.
  }
}
