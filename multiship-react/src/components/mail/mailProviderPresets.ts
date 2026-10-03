/**
 * A4.3+ — pre-canned config sets for the most common mail services.
 * Ops picks one from the modal dropdown and host/port/tls/etc. fill in
 * automatically — they only still have to enter their credentials +
 * from address. "Custom" leaves everything blank.
 *
 * Layout: one array per provider kind. The `values` object maps a
 * subset of the SPI's config keys to sensible defaults for that server.
 * Anything not in `values` stays blank and the user fills it.
 */

export interface MailPreset {
  id: string
  label: string
  description?: string
  values: Record<string, string>
}

export const MAIL_PRESETS: Record<string, MailPreset[]> = {
  SMTP: [
    {
      id: 'custom',
      label: 'Custom SMTP',
      description: 'Blank form — for self-hosted or niche servers.',
      values: {},
    },
    {
      id: 'gmail',
      label: 'Gmail / Google Workspace',
      description: 'Requires an App Password (2FA-enabled accounts only).',
      values: { host: 'smtp.gmail.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'o365',
      label: 'Office 365 / Outlook',
      description: 'Modern-auth accounts may need OAuth-issued app password.',
      values: { host: 'smtp.office365.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'outlook',
      label: 'Outlook.com / Hotmail',
      description: 'Personal Microsoft accounts.',
      values: { host: 'smtp-mail.outlook.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'yahoo',
      label: 'Yahoo Mail',
      description: 'Requires an App Password.',
      values: { host: 'smtp.mail.yahoo.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'zoho',
      label: 'Zoho Mail',
      description: 'Free / paid Zoho hosted mail.',
      values: { host: 'smtp.zoho.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'icloud',
      label: 'iCloud Mail',
      description: 'Requires an App-Specific Password from appleid.apple.com.',
      values: { host: 'smtp.mail.me.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'sendgrid-smtp',
      label: 'SendGrid SMTP',
      description: 'Alternative to the REST provider. Username is literally "apikey".',
      values: { host: 'smtp.sendgrid.net', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true', username: 'apikey' },
    },
    {
      id: 'mailgun-smtp',
      label: 'Mailgun SMTP',
      description: 'Region-specific — use smtp.eu.mailgun.org for EU.',
      values: { host: 'smtp.mailgun.org', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'ses-smtp-us-east-1',
      label: 'AWS SES SMTP — us-east-1',
      description: 'Use SES REST provider if you want IAM auth.',
      values: { host: 'email-smtp.us-east-1.amazonaws.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'ses-smtp-us-west-2',
      label: 'AWS SES SMTP — us-west-2',
      values: { host: 'email-smtp.us-west-2.amazonaws.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'ses-smtp-eu-west-1',
      label: 'AWS SES SMTP — eu-west-1',
      values: { host: 'email-smtp.eu-west-1.amazonaws.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'postmark-smtp',
      label: 'Postmark SMTP',
      description: 'Alternative to the REST provider. Username = server token.',
      values: { host: 'smtp.postmarkapp.com', port: '587', use_tls: 'true', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'mailtrap',
      label: 'Mailtrap Sandbox',
      description: 'Testing-only inbox. Never delivers real mail.',
      values: { host: 'sandbox.smtp.mailtrap.io', port: '2525', use_tls: 'false', use_ssl: 'false', auth_required: 'true' },
    },
    {
      id: 'postfix-localhost',
      label: 'Local Postfix / MailHog',
      description: 'Unauthenticated relay on localhost. Dev boxes only.',
      values: { host: 'localhost', port: '1025', use_tls: 'false', use_ssl: 'false', auth_required: 'false' },
    },
  ],
  SENDGRID: [
    { id: 'sendgrid', label: 'SendGrid REST', description: 'v3 mail/send endpoint.', values: {} },
  ],
  SES: [
    { id: 'ses-us-east-1', label: 'SES REST — us-east-1', values: { region: 'us-east-1' } },
    { id: 'ses-us-east-2', label: 'SES REST — us-east-2', values: { region: 'us-east-2' } },
    { id: 'ses-us-west-1', label: 'SES REST — us-west-1', values: { region: 'us-west-1' } },
    { id: 'ses-us-west-2', label: 'SES REST — us-west-2', values: { region: 'us-west-2' } },
    { id: 'ses-eu-west-1', label: 'SES REST — eu-west-1 (Ireland)', values: { region: 'eu-west-1' } },
    { id: 'ses-eu-central-1', label: 'SES REST — eu-central-1 (Frankfurt)', values: { region: 'eu-central-1' } },
    { id: 'ses-ap-south-1', label: 'SES REST — ap-south-1 (Mumbai)', values: { region: 'ap-south-1' } },
    { id: 'ses-ap-southeast-1', label: 'SES REST — ap-southeast-1 (Singapore)', values: { region: 'ap-southeast-1' } },
    { id: 'ses-ap-northeast-1', label: 'SES REST — ap-northeast-1 (Tokyo)', values: { region: 'ap-northeast-1' } },
  ],
  POSTMARK: [
    { id: 'postmark', label: 'Postmark REST', values: {} },
  ],
}

/** Fallback list when a provider's kind has no presets defined yet. */
export function presetsFor(kind: string | undefined): MailPreset[] {
  if (!kind) return []
  return MAIL_PRESETS[kind] ?? [{ id: 'custom', label: 'Custom', values: {} }]
}
