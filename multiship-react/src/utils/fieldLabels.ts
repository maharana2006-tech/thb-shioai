/**
 * Names the form fields the way the page labels them, so a notice can say
 * "Ship to name: max 35 characters" instead of a bare "Max 35 characters".
 */
const ADDRESS: Record<string, string> = {
  name: 'name', company: 'company', addressLine1: 'address line 1', addressLine2: 'address line 2',
  addressLine3: 'address line 3', city: 'city', state: 'state', postalCode: 'postal code',
  countryCode: 'country', phone: 'phone', email: 'email',
}
const TOP: Record<string, string> = {
  weight: 'Weight', declaredValue: 'Declared value', insuredValue: 'Insured value',
  length: 'Length', width: 'Width', height: 'Height', currency: 'Currency', incoterms: 'Incoterms',
  reasonForExport: 'Reason for export', clientCode: 'Client', carrier: 'Carrier', account: 'Account',
}
const ITEM: Record<string, string> = {
  description: 'description', sku: 'SKU', hsCode: 'HS code', countryOfOrigin: 'origin',
  quantity: 'quantity', unitValue: 'unit value',
}

/** "recipient.name" → "Ship to name"; "items[1].hsCode" → "Item 2 HS code"; unknown → null. */
export function fieldLabelFor(path: string): string | null {
  const addr = /^(sender|recipient)\.(\w+)$/.exec(path)
  if (addr) {
    const block = addr[1] === 'sender' ? 'Ship from' : 'Ship to'
    return ADDRESS[addr[2]] ? `${block} ${ADDRESS[addr[2]]}` : block
  }
  const item = /^items\[(\d+)\]\.(\w+)$/.exec(path)
  if (item) return `Item ${Number(item[1]) + 1}${ITEM[item[2]] ? ` ${ITEM[item[2]]}` : ''}`
  return TOP[path] ?? null
}
