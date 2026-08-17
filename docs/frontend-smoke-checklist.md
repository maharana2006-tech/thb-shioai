# Frontend browser smoke-verification checklist

Sprint 52 verification hardening — FE-M5 (responsive sidebar drawer,
Sprint 51 PR #167) and FE-M3 (client-error telemetry, same PR) shipped
without any browser-level smoke test. Unit coverage now exists in
`multiship-react/src/components/layout/Sidebar.mobile.test.tsx`,
`multiship-react/src/utils/errorReport.test.ts`, and
`multiship-react/src/components/errors/ErrorBoundaryTelemetry.test.tsx`,
but jsdom cannot exercise real breakpoints, real touch events,
real focus semantics, or a real network call to the backend sink.

This checklist is what QA (or a dev on smoke duty) walks through
before signing off any sprint that touches the workspace shell, the
error boundaries, or the `/api/v1/client-errors` endpoint.

E2E tooling (Playwright / Cypress) is intentionally NOT wired in this
repo yet — adding it is a separate framework-decision PR. When it
lands, most of this doc becomes an automated spec.

---

## Environment

- **Browsers to cover** (matrix — every listed browser × every listed
  viewport): Chromium ≥ 124, Firefox ≥ 128, Safari 17+ (macOS/iOS).
- **Viewports** (Chrome DevTools "Toggle device toolbar"):
  - `375 × 667` (iPhone SE-ish — **<md**, drawer mode)
  - `414 × 896` (iPhone Plus — **<md**, drawer mode)
  - `768 × 1024` (iPad portrait — **md exactly**, rail mode)
  - `1280 × 800` (small laptop — **md+**, rail mode)
  - `1920 × 1080` (desktop)
- **Backend**: any signed-in session against a dev backend that has
  `/api/v1/client-errors` exposed (the endpoint is permitAll, so
  no fixtures needed beyond a valid app bundle).
- **Build**: run `VITE_API_BASE_URL=/api/v1 npm run build && npm run preview`
  to smoke the production bundle, not the dev server (dev-only HMR
  can mask certain bundle-splitting issues).

---

## FE-M5 — Responsive sidebar drawer

Wired in `multiship-react/src/components/layout/Sidebar.tsx` +
`WorkspaceLayout.tsx` + `WorkspaceHeader.tsx`.

### F5-1. Rail vs. drawer breakpoint

**At 1280 × 800 (md+)**
- [ ] Sidebar is a permanent left rail (64 px collapsed / 224 px pinned).
- [ ] No hamburger button in the header.
- [ ] Content column has `md:ml-16` (unpinned) or `md:ml-56` (pinned)
      offset — page content does NOT sit under the rail.

**At 375 × 667 (<md)**
- [ ] Sidebar is off-screen by default (translate-x-full).
- [ ] Hamburger `☰` button appears in the top-left of the header
      (aria-label "Open navigation").
- [ ] Content column is full-bleed (no left margin).

**At 768 × 1024 (exactly md)**
- [ ] Rail behaviour (Tailwind's `md:` prevails at ≥768 px).
- [ ] No hamburger.

### F5-2. Opening the drawer

At 375 × 667:
- [ ] Tap the hamburger. Drawer slides in from the left over ~200 ms.
- [ ] Drawer is full 224 px wide, all nav labels visible.
- [ ] A dark backdrop covers the rest of the screen.
- [ ] The page content behind is dimmed but NOT scrollable while the
      drawer is open (verify by trying to scroll the background).
      NOTE: current implementation does not lock body scroll — file
      a follow-up if this is required.

### F5-3. Closing the drawer

Each of these should close it:
- [ ] Tap the backdrop (anywhere outside the drawer).
- [ ] Tap any nav item — drawer auto-closes AND navigates to that
      route (auto-close is the effect keyed on `activePath`).
- [ ] Pressing Escape.
      **KNOWN GAP**: current implementation does not handle Escape.
      Verify this is still broken and file a follow-up if so.

### F5-4. Accessibility on the drawer

**KNOWN GAPS** (Sprint 52 audit — file follow-ups if still broken):
- [ ] Focus does NOT trap inside the drawer when open. Tab past the
      last nav item and focus should stay in the drawer; today it
      escapes into the page behind.
- [ ] The `<nav>` element carries `aria-label="Primary"` but NOT
      `role="dialog"` + `aria-modal="true"` when in drawer mode.
      Screen readers announce it as a nav, not a modal.

If these gaps have been fixed since Sprint 51, tick them:
- [ ] Tab-loop stays inside the open drawer.
- [ ] First focusable child (brand button) is focused on drawer open.
- [ ] Shift+Tab from the first element wraps to the last.
- [ ] Escape closes the drawer AND restores focus to the hamburger.
- [ ] `role="dialog"` + `aria-modal="true"` present when open.

### F5-5. Cross-browser sanity

- [ ] Safari iOS 17: `100vh` height renders correctly (no bottom-bar
      cropping — Safari's dynamic viewport quirks).
- [ ] Firefox 128: backdrop `backdrop-blur-sm` degrades acceptably
      even if backdrop-filter is unsupported.
- [ ] Chrome mobile: swipe-to-close is NOT expected (not implemented);
      operators use the backdrop tap.

### F5-6. Print

- [ ] `window.print()` (or Ctrl-P) on any page: sidebar + header +
      drawer + backdrop are all hidden. Print bed is 4×6 label
      full-bleed with no left offset (that was the reason `print:ml-0`
      lives on the content column).

---

## FE-M3 — Client-side error telemetry

Wired in `multiship-react/src/components/errors/RouteErrorBoundary.tsx`,
`AppErrorBoundary.tsx`, and `multiship-react/src/utils/errorReport.ts`.
Sink is `POST /api/v1/client-errors` (unauthenticated, IP rate-limited).

### F3-1. RouteErrorBoundary catches a route crash

Trigger a render error in a route (temporarily wire a `throw new Error('smoke crash')`
inside e.g. `<OrdersWorkspace>` — do NOT commit that; revert before
merging).

- [ ] Page main area shows the rose-tinted "route couldn't render"
      panel. Sidebar and header remain interactive.
- [ ] Clicking "Try again" resets the boundary and re-renders.
- [ ] DevTools > Network shows a **POST /api/v1/client-errors** with
      status **202**.
- [ ] The request payload (Network > Request > Payload tab) has these
      fields:
  ```json
  {
    "path": "/orders",
    "message": "smoke crash",
    "stack": "Error: smoke crash\n    at OrdersWorkspace ...",
    "componentStack": "\n    at OrdersWorkspace\n    at RouteErrorBoundary ...",
    "userAgent": "Mozilla/5.0 ...",
    "ts": "2026-08-15T13:00:00.000Z"
  }
  ```
- [ ] Backend log shows a WARN line: `[client-error] ip=... ts=... path=/orders ... message=smoke crash ...`

### F3-2. AppErrorBoundary catches a top-level crash

Repeat F3-1 but with the throw wired outside all route boundaries
(e.g. inside `<App>` before `<RouterProvider>`).

- [ ] Full-screen slate fallback: "Something went wrong." with a
      Reload button.
- [ ] Reload button forces `window.location.reload()`.
- [ ] Same telemetry POST fires with the top-level error.

### F3-3. Dedup + rate limit

- [ ] Trigger the same crash three times in a row (e.g. click "Try again"
      on a boundary that keeps re-crashing the same way). Only ONE
      POST fires — the client dedups by `name:message:pathname`.
- [ ] Trigger 12 distinct crashes within 60 s. Only the first 10 fire
      — client rate limit is `MAX_REPORTS_PER_MINUTE = 10`.

### F3-4. Backend rate limiter does not surface a 429 to the client

- [ ] Manually fire 40 POSTs to `/api/v1/client-errors` from the same
      IP (a curl loop from the same machine). Server logs show
      "rate-limited, silent-drop" after ~30 requests, but the HTTP
      response is still **202** on every request. This prevents the
      SPA's own fetch error handler from firing on a 429 and looping
      back into telemetry.

### F3-5. No telemetry loop on network failure

- [ ] Chrome DevTools > Network > "Offline". Trigger a route crash.
      Boundary fallback still renders (no cascade). Confirm no
      exception in DevTools Console beyond the original crash.

### F3-6. Cross-browser sanity

- [ ] `fetch(..., { keepalive: true })` behaves in every target
      browser. Especially: navigating away mid-crash still delivers
      the POST (Safari has historically been quirky here).
- [ ] `credentials: 'include'` sends the session cookie so backend
      logs correlate `ip=... user=...` when signed in.

---

## Sign-off

| Env                        | Tester | Date | FE-M5 pass | FE-M3 pass | Notes |
|----------------------------|--------|------|------------|------------|-------|
| Chromium 124 / 375×667     |        |      |            |            |       |
| Chromium 124 / 1280×800    |        |      |            |            |       |
| Firefox 128 / 375×667      |        |      |            |            |       |
| Firefox 128 / 1280×800     |        |      |            |            |       |
| Safari 17 iOS / 375×667    |        |      |            |            |       |
| Safari 17 macOS / 1280×800 |        |      |            |            |       |

---

## Follow-ups discovered during the Sprint 52 audit

Reported in the verification-hardening PR body; file separately if
they aren't already tracked:

1. **FE-M5 missing focus trap** — `Sidebar.tsx` mobile drawer does not
   engage `useFocusTrap` (the hook added in Sprint 49 Tier 4 Fix 6).
   Fix: wrap the `<nav>` in a ref and call `useFocusTrap(mobileOpen, navRef)`.
2. **FE-M5 missing dialog semantics on mobile** — drawer keeps
   `aria-label="Primary"` in drawer mode; should also carry
   `role="dialog"` + `aria-modal="true"` when `mobileOpen`.
3. **FE-M5 missing Escape-to-close** — Escape does nothing while the
   drawer is open. Add a keydown listener that fires `onMobileClose`.
4. **FE-M5 no body-scroll lock** — page behind the drawer is still
   scrollable on touch devices. Add `overflow-hidden` on `<html>`
   while `mobileOpen`.

None of these was in scope to fix in this PR (production-code changes
were excluded); they are audit-time discoveries only.
