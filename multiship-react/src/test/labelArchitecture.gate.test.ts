/// <reference types="node" />
// @vitest-environment node
// (a source-tree scan needs file: URLs and node's fs, not jsdom)
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Phase 3 gate (Shipment & Label re-architecture) — none of the removed
 * label paths may come back: the old document page, its feature flag, its
 * payload type, and the dead orderService methods that hit the old
 * in-controller endpoints. Precise identifiers, not English words.
 */
const FORBIDDEN = [
  'LabelDocumentPage',
  'LabelDocumentPayload',
  'VITE_LABEL_V2',
  'headLabelPreviewPng',
  'labelPreviewPngUrl',
  'getLabelDocument(',
  'getLabelZpl(',
  '/label/zpl',
  '/label/pdf',
  '/label/preview.png',
]

const HERE = fileURLToPath(new URL('.', import.meta.url))
const SRC = join(HERE, '..')
const SELF = fileURLToPath(import.meta.url)

function walk(dir: string, out: string[]): void {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) walk(path, out)
    else if (/\.(ts|tsx)$/.test(name)) out.push(path)
  }
}

describe('label architecture gate', () => {
  it('no reference to the removed label page, flag or endpoints survives in src/', () => {
    const files: string[] = []
    walk(SRC, files)
    const hits: string[] = []
    for (const file of files) {
      if (file === SELF) continue
      const text = readFileSync(file, 'utf8')
      for (const token of FORBIDDEN) {
        if (text.includes(token)) hits.push(`${file.slice(SRC.length + 1)}  ::  ${token}`)
      }
    }
    expect(hits, `Removed label paths resurfaced:\n  ${hits.join('\n  ')}`).toEqual([])
  })
})
