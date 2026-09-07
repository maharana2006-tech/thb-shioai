import { describe, expect, it } from 'vitest'
import { parsePastedItems } from './PasteItemsModal'

describe('parsePastedItems', () => {
  it('parses tab-separated rows from a spreadsheet and skips a header row', () => {
    const text = [
      'Description\tSKU\tHS\tOrigin\tQty\tUnit\tWeight',
      'Leather wallets\tSKU-1\t4202.31.00\tus\t6\t28.00\t0.30',
      'Canvas belts\tSKU-2\t4203.30.10\tMX\t4\t12.50\t0.30',
      'Ceramic mugs\tSKU-3\t6912.00.00\tCN\t12\t4.75\t',
    ].join('\n')
    const { rows, skipped } = parsePastedItems(text)
    expect(skipped).toBe(0)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toEqual({ description: 'Leather wallets', sku: 'SKU-1', hsCode: '4202.31.00', countryOfOrigin: 'US', quantity: '6', unitValue: '28.00', weight: '0.30' })
    expect(rows[2].weight).toBe('')
  })

  it('parses comma-separated rows, tolerates missing trailing columns, defaults qty to 1', () => {
    const { rows } = parsePastedItems('Espresso machines,,8516.71.00,IT\nCoffee grinders,SKU-9,8509.40.00,US,4,32.5')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toMatchObject({ description: 'Espresso machines', sku: '', hsCode: '8516.71.00', countryOfOrigin: 'IT', quantity: '1', unitValue: '', weight: '' })
    expect(rows[1]).toMatchObject({ quantity: '4', unitValue: '32.5' })
  })

  it('counts rows with no description as skipped and ignores blank lines', () => {
    const { rows, skipped } = parsePastedItems('\n\tSKU-X\t1234.56\n\nMugs\t\t6912.00\tCN\t2\t3\n')
    expect(rows).toHaveLength(1)
    expect(skipped).toBe(1)
  })
})
