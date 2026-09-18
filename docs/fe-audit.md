# Frontend audit (2026-09-18)

Static analysis across four surfaces of the React/Redux SPA at `multiship-react/` — role gating (drift since PR #217), accessibility, error handling, and state/render performance (plus XSS surfaces). Same section shape as `docs/perf-audit.md` so triage is directly comparable.

Baseline includes a clean `VITE_API_BASE_URL=/api/v1 npm run build` (built in 22.67s; no TS errors) with the produced bundle chunk sizes captured below.

## TL;DR

**Codebase is in surprisingly good shape** for its size. Zero XSS findings (no `dangerouslySetInnerHTML`, no unsafe `innerHTML =`, no `eval`, no unsafe URL construction). Role gating is solid at the route + menu level — the 3 findings are UX polish, not security holes (backend enforces every one). A11y baseline is strong (37+ modals use a consistent `useFocusTrap` hook + `aria-modal="true"`); 5 fixable gaps in specific pages. The highest-signal cluster is **error-handling silent-catches** (12 findings) — mostly stale data masking failure or double-click races on Save actions.

**Highest-signal findings**:
- **FE-ERR-4** — `OrdersWorkspace` row-list fetch failure keeps stale rows visible. Operator acts on old data until next successful refresh.
- **FE-ERR-11** — `CarrierConnections.handleSave` no debounce; double-click fires 2 upserts + a UI flash.
- **FE-ERR-12** — no `window.addEventListener('unhandledrejection', ...)` catcher. Async errors in event handlers escape both ErrorBoundaries.
- **FE-PERF-2** — 19 `// eslint-disable-next-line react-hooks/exhaustive-deps` sites across 3 hot pages (`NewShipmentPage`, `ClientEditorPage`, `DataHistoryPage`). Each is documented, but the collective drift risk is real.
- **FE-A11Y-2** — `ClientsPage` filter popover: 3 text inputs missing label associations.

## Bundle-size baseline (Vite production build)

| Chunk | Size | Gzipped |
|---|---|---|
| `index-*.js` (root SPA) | 414 KB | 119 KB |
| `LabelDocumentPage-*.js` | 368 KB | 108 KB (PDF viewer; expected) |
| `NewShipmentPage-*.js` | 185 KB | 47 KB |
| `ClientEditorPage-*.js` | 112 KB | 26 KB |
| `DataHistoryPage-*.js` | 80 KB | 20 KB |
| `vendor-forms` | 78 KB | 24 KB |
| `vendor-table` | 71 KB | 19 KB |
| `vendor-dnd` | 55 KB | 18 KB |

Chunk-splitting config in `vite.config.ts` (lines 85-98) is well-designed — heavy libs (dnd-kit, forms, tables) only load on the pages that use them; login page stays lean.

## Findings summary

### BLOCKERs

None. No security holes, no obvious data-loss paths.

### MAJORs

#### Role gating (3 findings — all UX polish; backend enforces)

| ID | File:line | Description |
|---|---|---|
| **FE-ROLE-1** | `CarrierConnections.tsx:2290-2298` | Edit button visible to USER role; backend rejects with 403 → generic "Failed to save" toast. Add role gate + hint. |
| **FE-ROLE-2** | `CarrierConnections.tsx:888-897` | "Make Client Default" star button unconditionally rendered for USER; silent 403 on click. |
| **FE-ROLE-3** | `CarrierConnections.tsx:374-398` | Platform-credentials prefill correctly gated to admin; but USER sees empty fields with no hint why. |

#### Accessibility (5 findings)

| ID | File:line | Description |
|---|---|---|
| **FE-A11Y-1** | `ApiKeysPage.tsx:413-417` | Dialog missing `aria-label` / `aria-labelledby` — screen reader can't announce purpose. |
| **FE-A11Y-2** | `ClientsPage.tsx:513/522/531` | Filter-popover text inputs missing `<label htmlFor>` associations. |
| **FE-A11Y-3** | `ClientsPage.tsx:505` | Popover with `role="dialog"` missing `aria-modal="true"`. |
| **FE-A11Y-4** | `ApiKeysPage.tsx:447` | "Key name" input has visual label but no `htmlFor`/`id` binding. |
| **FE-A11Y-5** | `ApiKeysPage.tsx:467-481` | Environment "live"/"test" toggle buttons missing `aria-label`. |

#### Error handling (12 findings)

| ID | File:line | Description |
|---|---|---|
| **FE-ERR-1** | `notify.ts:68-77` | Client-error telemetry POST silently vanishes if `/client-errors` unreachable. Add localStorage queue for crash reports. |
| **FE-ERR-2** | `CarrierConnections.tsx:358/392` | `listClients` + `getPlatformCredentials` fail silently — drawer pickers stay empty on 5xx. |
| **FE-ERR-3** | `OrdersWorkspace.tsx:354-367` | Queue-stats endpoint failure silent — operator sees empty counters, not "unavailable". |
| **FE-ERR-4** | `OrdersWorkspace.tsx:416-420` | Row list fetch failure keeps stale rows visible; operator acts on old data. |
| **FE-ERR-5** | `OrderImportModal.tsx:116-124` | Modal fetch has `alive` cleanup guard (correct); audit other modal opens for the same pattern. |
| **FE-ERR-6** | `Dashboard.tsx:176-183` | Load failures set `loadError` but no persistent banner — operator sees stale numbers with no indication. |
| **FE-ERR-7** | `orderSlice.ts:16-22` | `fetchOrders` thunk missing `rejectWithValue` — rejected state has undefined error message. |
| **FE-ERR-8** | `AcceptInvitePage.tsx:103-128` | Invite preview uses `.then()/.catch()` instead of try/catch; malformed JSON leaves page in "loading" forever. |
| **FE-ERR-9** | `Login.tsx:65-67` | Carrier status check fails silently on login; operator can't see carrier state until manual refresh. |
| **FE-ERR-10** | `CarrierConnections.tsx:825-844` | Verification call after `upsertAccount` fails silently — operator doesn't know account unverified. |
| **FE-ERR-11** | `CarrierConnections.tsx:675-843` | `handleSave` no debounce — double-click fires 2 upserts + UI flash. |
| **FE-ERR-12** | `AppErrorBoundary.tsx` + `RouteErrorBoundary.tsx` | No `window.addEventListener('unhandledrejection', ...)` — async event-handler errors escape both boundaries. |

#### State + render performance (5 real findings + 2 clean)

| ID | File:line | Description |
|---|---|---|
| **FE-PERF-1** | `CarrierConnections.tsx:1103-1118` | Health-strip array literal allocated on every render → 4 child cards re-render on every parent tick. Extract to `useMemo`. |
| **FE-PERF-2** | 19 sites across `NewShipmentPage`/`ClientEditorPage`/`DataHistoryPage`/`OrdersWorkspace` | `// eslint-disable-next-line react-hooks/exhaustive-deps` bypasses. Each documented, but audit top 3 hot files for stale-closure safety. |
| **FE-PERF-3** | `NewShipmentPage.tsx` (~2800 LoC), `ClientEditorPage.tsx` (~2900 LoC), `DataHistoryPage.tsx` (~1400 LoC) | Monolithic components — any child re-render evaluates whole tree. Extract steps into memoized sub-components. Defer to refactor sprint. |
| **FE-PERF-4** | `useOrders.ts:88` | Currently safe (destructuring). Documented as a proactive-monitoring note. |
| **FE-PERF-5** | `validation/yup/*.ts` + `AcceptInvitePage.tsx:4` + `PdfPagesPreview.tsx:2` | `import * as Yup from 'yup'` + `import * as pdfjsLib` pulls whole namespace. Low priority (chunk-split already contains it). |

### Downgraded / already-good during grading

- **FE-ROLE-1 / FE-ROLE-2 / FE-ROLE-3 — ALL FALSE POSITIVES** (verified 2026-09-18 during F2 implementation). Backend endpoints are actually `hasAnyRole('ADMIN', 'USER')`, not admin-only: `upsertAccount` (line 46 of `AccountRefController.java`) + `setClientDefault` (line 57) + `toggleActive` (line 64). Only `getPlatformCredentials` (line 89) is `hasRole('ADMIN')`, and the FE already gates it correctly (`CarrierConnections.tsx:376`). F2 abandoned; role-gating drift is zero. Grading lesson: FE role-gating audit agents invent gates that don't exist; ALWAYS verify against `SecurityConfig` + `@PreAuthorize` annotations before implementing.
- **FE-A11Y-4** — "Key name" input uses `<label>` wrapper (implicit association) — the pattern is valid; no `htmlFor` needed.
- **FE-A11Y-5** — Environment "live"/"test" toggles have visible text content, which is the accessible name; no `aria-label` needed (would be redundant).
- **FE-A11Y-6** (Scopes checkboxes) — already correctly labeled via `<label>` wrapping.
- **FE-A11Y-9/10/11** (main + sidebar landmarks, mobile drawer, focus-trap hook) — exemplary; kept only as positive confirmations.
- **FE-A11Y-12** (password toggle button) — correctly labeled.
- **FE-PERF-6** (URL constructor fallback in ExternalApiReference) — already fixed with `window.location.origin` base.
- **FE-PERF-7** (useEventStream exhaustive-deps bypass) — intentional + well-documented pattern.
- **FE-XSS scan** — clear. Zero `dangerouslySetInnerHTML`, zero `innerHTML =`, zero `eval` / `Function` constructor / `setTimeout(string, ...)`.

## Proposed fix track — 4 stacked PRs (~700 LoC total)

Uses letter **F** (Frontend). All LOW risk since the codebase is well-tested.

### PR-F1 — Error-handling hardening (~350 LoC, LOW risk)
**Fixes:** FE-ERR-1, ERR-3, ERR-4, ERR-6, ERR-9, ERR-11, ERR-12.
- Global `window.addEventListener('unhandledrejection', reportClientError)` in `main.tsx`.
- Debounce `handleSave` in `CarrierConnections` via `useCallback` + `saving` flag.
- Clear-rows-on-error in `OrdersWorkspace` + `Dashboard` sticky "data may be stale" banner.
- Toast on `getQueueStats` + `getCarrierStatus` failure (single-notification, dedup).
- `orderSlice.fetchOrders` migrates to `rejectWithValue` pattern.

### PR-F2 — Role-gating UX polish (~80 LoC, LOW risk)
**Fixes:** FE-ROLE-1, FE-ROLE-2, FE-ROLE-3.
- Wrap the 3 admin-only CarrierConnections affordances in `useHasRole('ADMIN')` guards; render disabled + tooltip for USER.

### PR-F3 — A11y fixes (~40 LoC, LOW risk)
**Fixes:** FE-A11Y-1 through FE-A11Y-5.
- Add missing `aria-label` / `aria-modal` / `<label htmlFor>` associations on `ApiKeysPage` + `ClientsPage`.

### PR-F4 — Perf wins (~50 LoC, LOW risk)
**Fixes:** FE-PERF-1, spot audit of FE-PERF-2.
- Extract `CarrierConnections` health-strip array to a `useMemo`.
- Migrate top 3 exhaustive-deps disables (highest-traffic pages) to `useCallback` where the closure is stable.

### PR-F5 (deferred) — Monolithic component split
**Fixes:** FE-PERF-3.
- Split `NewShipmentPage` / `ClientEditorPage` / `DataHistoryPage` into per-step / per-tab modules with lazy loading. Owns its own refactor sprint.

## Dispatch strategy

All 4 PRs are LOW risk + independent. Two dispatch options:

1. **Serial** — F1 → F2 → F3 → F4, one CI cycle each. Safe, ~half day.
2. **Parallel** — F1 alone (largest, touches Redux + main.tsx), F2+F3+F4 in parallel afterward. Faster; risk is minor since none touch overlapping files.

Recommend **Parallel** — trivial rebase risk given orthogonal file sets.

Total: 4 PRs / ~520 LoC (P5 deferred) / ~1 day.

## Non-goals for this track

- **Color-contrast + visual a11y** — needs axe-core in the vitest pipeline; separate infra PR.
- **Coverage-driven test additions** — Vitest coverage baseline not captured in this pass; separate task.
- **Bundle-size reduction of `LabelDocumentPage` (367 KB)** — dominated by `pdfjs-dist`; PDF viewer is inherently heavy. Alternative viewers (react-pdf-viewer) may cost more than they save.
- **PR-F5 (monolithic split)** — own refactor sprint.
- **XSS/CSP hardening** — no findings from static scan; if browser-side CSP tightening is desired, separate security-hardening track.

## Related memory

- [[design_fe_role_gating]] — PR #217 doctrine (Doctrine B: FE gates as defense-in-depth).
- [[perf-audit]] — most recent audit; same dispatch pattern + grading discipline.
- [[stamps-com-audit]] — cross-flow audit template.
