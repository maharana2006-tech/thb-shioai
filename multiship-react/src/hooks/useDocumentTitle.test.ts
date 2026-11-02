import { afterEach, describe, expect, it } from 'vitest'
import { renderHook, cleanup } from '@testing-library/react'
import { useDocumentTitle } from './useDocumentTitle'

/**
 * BUG-007 — the browser-tab title stayed on the Vite boilerplate
 * "multiship-react" because no page set it. These pin the hook's
 * three contract rules: set-on-mount, restore-on-unmount, skip-on-null.
 */

afterEach(() => { cleanup(); document.title = 'baseline' })

describe('useDocumentTitle', () => {
  it('sets document.title to "title · suffix" on mount', () => {
    document.title = 'baseline'
    renderHook(() => useDocumentTitle('Dashboard'))
    expect(document.title).toBe('Dashboard · Multiship')
  })

  it('restores the previous title on unmount', () => {
    document.title = 'baseline'
    const { unmount } = renderHook(() => useDocumentTitle('Dashboard'))
    expect(document.title).toBe('Dashboard · Multiship')
    unmount()
    expect(document.title).toBe('baseline')
  })

  it('honours an explicit suffix', () => {
    renderHook(() => useDocumentTitle('Settings', 'Multiship Admin'))
    expect(document.title).toBe('Settings · Multiship Admin')
  })

  it('skips when title is null or empty — useful while data loads', () => {
    document.title = 'baseline'
    renderHook(() => useDocumentTitle(null))
    expect(document.title).toBe('baseline')
    renderHook(() => useDocumentTitle(''))
    expect(document.title).toBe('baseline')
  })
})
