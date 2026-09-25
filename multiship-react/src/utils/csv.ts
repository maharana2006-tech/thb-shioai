/** CSV for a spreadsheet: quoted where needed, BOM so Excel reads UTF-8. */
export const csvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const str = String(value)
  if (/[",\r\n]/.test(str)) return `"${str.replace(/"/g, '""')}"`
  return str
}

/** Save rows (header first) as a .csv the browser downloads. */
export function downloadCsv(filename: string, matrix: string[][]) {
  const csv = matrix.map((row) => row.map(csvCell).join(',')).join('\r\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
