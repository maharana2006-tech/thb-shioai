/**
 * Button design tokens — the espresso/cream button system used across the
 * app's toolbars and actions. One source of truth so every button (workspace,
 * Order Intake, API section, modals) reads the same.
 *
 *   PRIMARY  — dark espresso fill, cream text. The main action on a surface.
 *   GHOST    — white/cream fill, sand border, cocoa text. Secondary actions.
 *   *_SM     — compact variants so a row of actions fits on one line.
 *
 * All four share the same disabled treatment and hover transition.
 */

/** Large primary (dark) — e.g. "New shipment", "Fetch from WMS". */
export const BTN_PRIMARY =
  'inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none'

/** Large ghost (light) — pairs with BTN_PRIMARY, e.g. "Refresh". */
export const BTN_GHOST =
  'inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'

/** Compact primary — for dense rows of actions. */
export const BTN_PRIMARY_SM =
  'inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none'

/** Compact ghost — for dense rows of actions. */
export const BTN_GHOST_SM =
  'inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'
