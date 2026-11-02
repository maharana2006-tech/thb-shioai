import { describe, expect, it } from 'vitest'
import { configLabel } from './MailProviderFormModal'

/** BUG-136 — provider settings are labelled in plain words. */
describe('configLabel', () => {
  it('maps the known setting keys', () => {
    expect(configLabel('from_address')).toBe('From address')
    expect(configLabel('serverToken')).toBe('Server token')
    expect(configLabel('accessKeyId')).toBe('Access key ID')
    expect(configLabel('secretAccessKey')).toBe('Secret access key')
  })
  it('turns an unknown snake/camel key into words', () => {
    expect(configLabel('replyToAddress')).toBe('Reply to address')
    expect(configLabel('max_retries')).toBe('Max retries')
  })
})
