// Light / dark theme store. The user's preference is 'system' | 'light' |
// 'dark'; the resolved value ('light' | 'dark') is stamped on <html> as
// data-theme so the CSS token overrides in App.css take effect. 'system'
// follows the OS and updates live.
export type ThemePref = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const KEY = 'ms:theme'
const listeners = new Set<() => void>()

export function getThemePref(): ThemePref {
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch { /* storage blocked */ }
  return 'system'
}

function systemPrefersDark(): boolean {
  try { return window.matchMedia('(prefers-color-scheme: dark)').matches } catch { return false }
}

export function resolveTheme(pref: ThemePref = getThemePref()): ResolvedTheme {
  return pref === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : pref
}

/** Stamp the resolved theme on <html>. Called at boot and on every change. */
export function applyTheme(pref: ThemePref = getThemePref()): void {
  try { document.documentElement.setAttribute('data-theme', resolveTheme(pref)) } catch { /* no DOM */ }
}

export function setThemePref(pref: ThemePref): void {
  try { localStorage.setItem(KEY, pref) } catch { /* storage blocked */ }
  applyTheme(pref)
  listeners.forEach((fn) => fn())
}

export function subscribeTheme(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

// Follow the OS when the preference is 'system'.
try {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (getThemePref() === 'system') { applyTheme('system'); listeners.forEach((fn) => fn()) }
  })
} catch { /* matchMedia unsupported */ }
