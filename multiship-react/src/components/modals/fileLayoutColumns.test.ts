import { describe, expect, it } from 'vitest'
import { fileLayoutColumns } from './OrderImportModal'

describe('fileLayoutColumns', () => {
  it('keeps the client file’s order and names, and leaves out columns with no field', () => {
    const cols = fileLayoutColumns([
      { name: 'CLIENT_ID', field: 'clientCode' },
      { name: 'ATTENTION', field: 'recipientName' },
      { name: 'ONE_RATE', field: null },
      { name: 'SHIPVIA_CD', field: 'serviceType' },
      { name: 'GROUP_ID', field: 'reference' },
    ])
    expect(cols?.map((c) => [c.name, c.key])).toEqual([
      ['CLIENT_ID', 'clientCode'], ['ATTENTION', 'recipientName'], ['SHIPVIA_CD', 'serviceType'], ['GROUP_ID', 'reference'],
    ])
  })

  it('uses our own layout for a file in it, or when the columns are unknown', () => {
    expect(fileLayoutColumns([{ name: 'orderRef', field: 'orderRef' }, { name: 'clientCode', field: 'clientCode' }])).toBeNull()
    expect(fileLayoutColumns(null)).toBeNull()
  })
})
