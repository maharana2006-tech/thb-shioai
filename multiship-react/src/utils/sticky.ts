/** Persist the last-used value for a field so it prefills next time (sticky default). */
export const readSticky = (key: string, fallback: string): string => {
  try {
    return localStorage.getItem(key) || fallback
  } catch {
    return fallback
  }
}
export const writeSticky = (key: string, value: string): void => {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* ignore storage errors */
  }
}
