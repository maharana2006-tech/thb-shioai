import { describe, expect, it } from 'vitest'
import { alertDescription, alertTitle } from './MyNotificationSubscriptionsPage'

/** BUG-143 — alerts get a plain title and the author's variable list is dropped. */
describe('MyNotificationSubscriptionsPage copy', () => {
  it('turns a template key into a readable title', () => {
    expect(alertTitle('STAMPS.LOW_FUNDS_ALERT')).toBe('Stamps · Low funds alert')
    expect(alertTitle('QA_20261025.TPL1')).toBe('Qa 20261025 · Tpl1')
  })
  it('strips the trailing Vars note', () => {
    expect(alertDescription('Sent when the balance drops. Vars: accountNumber, available.')).toBe('Sent when the balance drops.')
    expect(alertDescription('Plain text')).toBe('Plain text')
  })
})
