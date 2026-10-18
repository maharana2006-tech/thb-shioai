/** CSV for a spreadsheet: quoted where needed, BOM so Excel reads UTF-8. */
export const csvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  let str = String(value)
  // BUG-076: formula injection guard. Excel / Numbers / LibreOffice
  // evaluate a cell that starts with =, +, -, @ (or TAB) as a formula —
  // a name like '=HYPERLINK(...)' is the classic exfiltration vector.
  // Prefix a single quote (which the spreadsheets strip on display).
  // Mirrors the backend-side guard in ClientServiceImpl.csvCell.
  if (str.length > 0) {
    const c = str.charAt(0)
    if (c === '=' || c === '+' || c === '-' || c === '@' || c === '\t') {
      str = "'" + str
    }
  }
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
