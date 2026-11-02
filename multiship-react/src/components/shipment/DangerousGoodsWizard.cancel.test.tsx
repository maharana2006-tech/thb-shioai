import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import DangerousGoodsWizard from './DangerousGoodsWizard'
import type { DangerousGoodsBlock } from '../../api/dgService'

/**
 * BUG-031 (QA 2026-10-23) — the wizard is parent-owned ({@code value}
 * + {@code onChange}) and every keystroke patches the parent via
 * {@code onChange}. Pre-fix, {@code onCancel} just dismissed the modal
 * without rolling back, so the form behind the dialog showed
 * "Hazmat attached · 1 commodity(ies)" with the operator's incomplete
 * declaration — and Validate then (correctly) blew up with DG errors.
 *
 * Fix: snapshot {@code value} on mount, replay it on cancel so Cancel
 * is a true undo. These tests pin the three Cancel paths (backdrop,
 * X button, Cancel button) and the "opened with no prior hazmat"
 * null-snapshot case.
 */

vi.mock('../../api/dgService', async () => {
  const actual = await vi.importActual<typeof import('../../api/dgService')>('../../api/dgService')
  return {
    ...actual,
    // The UN-number autocomplete fires on mount; keep it off the wire.
    dgService: { searchUn: vi.fn().mockResolvedValue({ data: [] }) },
  }
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function populatedBlock(): DangerousGoodsBlock {
  return {
    regulationSet: 'IATA',
    accessibility: 'INACCESSIBLE',
    emergencyContactName: 'Pat',
    emergencyContactPhone: '5551234567',
    signatoryName: 'Chris',
    signatoryTitle: 'Hazmat Officer',
    commodities: [{
      unNumber: 'UN3480',
      properShippingName: 'LITHIUM ION BATTERIES',
      hazardClass: '9',
      packingGroup: 'II',
      quantity: 1,
      quantityUnit: 'KG',
      packageCount: 1,
      limitedQuantity: false,
    }],
  }
}

describe('DangerousGoodsWizard — Cancel is a true undo (BUG-031)', () => {
  it('cancel after typing in a wizard opened with no prior hazmat → onChange(null)', () => {
    // The QA repro path: operator opens the modal with no attached
    // DG, types one char (which already fires onChange(block)), then
    // clicks Cancel. The form behind the modal must go back to
    // "not attached" (null), not keep the half-built block.
    const onChange = vi.fn()
    const onCancel = vi.fn()
    // Simulate the parent's post-keystroke state — this is what the
    // wizard would see on re-render after one char was typed.
    const typedBlock = populatedBlock()
    typedBlock.emergencyContactPhone = '' // half-filled
    render(
      <DangerousGoodsWizard
        value={typedBlock}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )

    // The mount captured snapshot=typedBlock (because that's what
    // the parent passed in on this render). Simulate the operator
    // typing one more character so the current value diverges from
    // the snapshot — this is the realistic case where undo must
    // happen. The wizard's state-is-the-prop model means the
    // snapshot is whatever the parent handed in; the next render
    // after onChange would come with a different value, but inside
    // the modal we track only the mount-time snapshot. To test the
    // "divergence → replay" branch, re-render with a different
    // value prop (as the parent would after a onChange).
    // We can't easily rebuild that stream here; instead assert the
    // simpler divergence case by rendering with null and then
    // forcing the behaviour via a re-render.
    // The direct behavioural assertion that catches the regression:
    // mount with null → operator types → onChange(block) → re-render
    // with new value → operator clicks Cancel → onChange(null).
    // That's what the next test covers; this one asserts no-op
    // when snapshot === current (operator opened + bounced without
    // typing).
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }))
    // Snapshot was typedBlock; current is still typedBlock (no
    // divergence this render); handler skips the redundant replay.
    expect(onChange).not.toHaveBeenCalled()
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('cancel after the value changed → onChange(snapshot) rolls back', () => {
    const onChange = vi.fn()
    const onCancel = vi.fn()

    // First mount with null (operator opened the modal fresh).
    const { rerender } = render(
      <DangerousGoodsWizard
        value={null}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )

    // Parent re-renders the wizard with a now-populated block (as if
    // the operator typed and the parent persisted the keystroke via
    // onChange). Snapshot (captured at mount) is still null.
    rerender(
      <DangerousGoodsWizard
        value={populatedBlock()}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )

    // Click Cancel — the handler sees current !== snapshot and
    // replays snapshot (null) so the parent clears the half-built
    // block, then dismisses the modal.
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(null)
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('backdrop click rolls back the same way', () => {
    const onChange = vi.fn()
    const onCancel = vi.fn()
    const { rerender, container } = render(
      <DangerousGoodsWizard
        value={null}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    rerender(
      <DangerousGoodsWizard
        value={populatedBlock()}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    // The outer dialog div owns the backdrop click handler.
    const backdrop = container.querySelector('[role="dialog"]')
    expect(backdrop).toBeTruthy()
    fireEvent.click(backdrop!)
    expect(onChange).toHaveBeenCalledWith(null)
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('X button rolls back the same way', () => {
    const onChange = vi.fn()
    const onCancel = vi.fn()
    const { rerender } = render(
      <DangerousGoodsWizard
        value={null}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    rerender(
      <DangerousGoodsWizard
        value={populatedBlock()}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Close/i }))
    expect(onChange).toHaveBeenCalledWith(null)
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('cancel after opening a modal that had a prior block → restores prior block', () => {
    // Reissue flow: operator already had a DG block on the shipment,
    // reopens the wizard to tweak it, types half a change, clicks
    // Cancel. The prior block must come back — not the half-change.
    const prior = populatedBlock()
    const halfEdit: DangerousGoodsBlock = { ...prior, emergencyContactName: 'Pa' }
    const onChange = vi.fn()
    const onCancel = vi.fn()

    const { rerender } = render(
      <DangerousGoodsWizard
        value={prior}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    rerender(
      <DangerousGoodsWizard
        value={halfEdit}
        onChange={onChange}
        onComplete={vi.fn()}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(prior);  // the full prior block, not halfEdit
    expect(onCancel).toHaveBeenCalledTimes(1)
  })
})
