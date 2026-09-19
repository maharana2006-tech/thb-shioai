import { describe, expect, it } from 'vitest'
import {
  emptyImporterBrokerDraft,
  importerBrokerDraftValid,
  type ImporterBrokerDraft,
} from './_types'

/**
 * F5-B — coverage for the ImporterBrokerDraft utilities extracted
 * from the top of ClientEditorPage. Pinned separately because the
 * BUSINESS-vs-RECEIVER validation shortcut is easy to regress if
 * someone rewrites the guard "all required fields" style.
 */

describe('emptyImporterBrokerDraft', () => {
  it('starts filled=false with BUSINESS + all fields blank', () => {
    const d = emptyImporterBrokerDraft()
    expect(d.filled).toBe(false)
    expect(d.importerType).toBe('BUSINESS')
    expect(d.countries).toEqual([])
    expect(d.importerName).toBe('')
    expect(d.brokerName).toBe('')
    expect(d.incoterms).toBe('')
  })
})

describe('importerBrokerDraftValid', () => {
  it('returns true when filled=false regardless of contents', () => {
    const d: ImporterBrokerDraft = {
      ...emptyImporterBrokerDraft(),
      filled: false,
      importerName: 'stale-half-typed-name',
    }
    expect(importerBrokerDraftValid(d)).toBe(true)
  })

  it('returns true when filled=true + RECEIVER (no identity required)', () => {
    // DAP terms — the receiver IS the importer of record; skip the
    // BUSINESS identity block entirely.
    const d: ImporterBrokerDraft = {
      ...emptyImporterBrokerDraft(),
      filled: true,
      importerType: 'RECEIVER',
    }
    expect(importerBrokerDraftValid(d)).toBe(true)
  })

  it('requires 5 BUSINESS identity fields to be non-blank', () => {
    const base: ImporterBrokerDraft = {
      ...emptyImporterBrokerDraft(),
      filled: true,
      importerType: 'BUSINESS',
      importerName: 'Acme Inc',
      importerCountry: 'US',
      importerAddress1: '123 Main St',
      importerCity: 'Chicago',
      importerPostcode: '60601',
    }
    expect(importerBrokerDraftValid(base)).toBe(true)

    // Whitespace-only counts as blank.
    expect(importerBrokerDraftValid({ ...base, importerName: '  ' })).toBe(false)
    expect(importerBrokerDraftValid({ ...base, importerCountry: '' })).toBe(false)
    expect(importerBrokerDraftValid({ ...base, importerAddress1: '' })).toBe(false)
    expect(importerBrokerDraftValid({ ...base, importerCity: '' })).toBe(false)
    expect(importerBrokerDraftValid({ ...base, importerPostcode: '' })).toBe(false)
  })
})
