import { describe, expect, it } from 'vitest'
import { resolveWorkspaceRouteKey } from './workspaceRoutes'

describe('resolveWorkspaceRouteKey', () => {
  it('BUG-181: New shipment / New return light the Orders rail item', () => {
    expect(resolveWorkspaceRouteKey('/orders/new')).toBe('orders')
    expect(resolveWorkspaceRouteKey('/orders/new/return')).toBe('orders')
  })

  it('keeps the legacy /orders/history on Bulk Mailer', () => {
    expect(resolveWorkspaceRouteKey('/orders/history')).toBe('bulk')
  })
})
