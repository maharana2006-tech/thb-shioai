import { describe, expect, it } from 'vitest'
import { shipmentSchema } from './shipmentSchema'

const errorsFor = async (extraPackages: unknown[]) => {
  try {
    await shipmentSchema.validateAt('extraPackages', { extraPackages }, { abortEarly: false })
    return []
  } catch (e) {
    return (e as { errors: string[] }).errors
  }
}

describe('shipmentSchema — boxes 2..N', () => {
  it('holds box 2 to the same 150 lb limit as box 1, naming the box', async () => {
    expect(await errorsFor([{ weight: '180', needsDims: false }])).toEqual(['Box 2: weight exceeds the 150 lb carrier limit'])
    expect(await errorsFor([{ weight: '2', needsDims: false }, { weight: '', needsDims: false }]))
      .toEqual(['Box 3: weight is required'])
  })

  it('needs the size of a custom box, but not of one with carrier packaging', async () => {
    const custom = await errorsFor([{ weight: '2', length: '', width: '8', height: '6', needsDims: true }])
    expect(custom).toEqual(['Box 2: length is required'])
    expect(await errorsFor([{ weight: '2', length: '', width: '', height: '', needsDims: false }])).toEqual([])
  })
})
