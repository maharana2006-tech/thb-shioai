import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { VirtualizedCommodityList } from './CustomsWizard'
import type { CustomsItem } from '../../api/customsService'

/**
 * Sprint 48 — regression guard on the virtualization work.
 *
 * <p>Naïve {@code items.map(...)} rendering hit jank around 30 items and
 * became unusable past 100. This test asserts that regardless of the
 * items array length, only a bounded set of rows is mounted in the DOM
 * — proving the virtualizer is doing its job.
 *
 * <p>Verification strategy: render 500 items, count description inputs
 * (one per row). With virtualization on, we expect ~10-20 rendered (7
 * visible in a 60vh container + 5 overscan × 2 sides = ~17 max). Without
 * virtualization the count would be 500.
 */
describe('VirtualizedCommodityList', () => {
  function makeItems(count: number): CustomsItem[] {
    return Array.from({ length: count }, (_, i) => ({
      description: `Item ${i + 1}`,
      hsCode: '',
      countryOfOrigin: 'US',
      quantity: 1,
      unitValue: 10,
      sku: `SKU-${i + 1}`,
    }))
  }

  it('mounts only a bounded set of rows when items.length is large', () => {
    const onUpdate = vi.fn()
    const onRemove = vi.fn()

    render(
      <VirtualizedCommodityList
        items={makeItems(500)}
        onUpdate={onUpdate}
        onRemove={onRemove}
      />,
    )

    // Each row has one Description input (placeholder "Description").
    const inputs = screen.getAllByPlaceholderText('Description')
    // jsdom returns 0 for getBoundingClientRect by default; @tanstack/react-virtual
    // then renders overscan rows only. We assert the bound is way below 500.
    expect(inputs.length).toBeGreaterThan(0)
    expect(inputs.length).toBeLessThan(50)
  })

  it('mounts every row when items.length is small (no unnecessary virtualization overhead)', () => {
    const onUpdate = vi.fn()
    const onRemove = vi.fn()

    render(
      <VirtualizedCommodityList
        items={makeItems(3)}
        onUpdate={onUpdate}
        onRemove={onRemove}
      />,
    )

    // Small list: every row should render.
    const inputs = screen.getAllByPlaceholderText('Description')
    expect(inputs.length).toBe(3)
  })

  it('surfaces individual row data', () => {
    const onUpdate = vi.fn()
    const onRemove = vi.fn()

    render(
      <VirtualizedCommodityList
        items={makeItems(5)}
        onUpdate={onUpdate}
        onRemove={onRemove}
      />,
    )

    // The values come through into the DOM (not just placeholders).
    const first = screen.getByDisplayValue('Item 1')
    expect(first).toBeInTheDocument()
  })
})
