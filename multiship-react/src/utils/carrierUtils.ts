export type SupportedCarrierId = 'fedex' | 'ups' | 'usps' | 'dhl'

const carrierRegistry: Record<
  SupportedCarrierId,
  {
    displayName: string
    aliases: string[]
  }
> = {
  fedex: {
    displayName: 'FedEx',
    aliases: ['fedex', 'fdx', 'f77'],
  },
  ups: {
    displayName: 'UPS',
    aliases: ['ups', 'p80'],
  },
  usps: {
    displayName: 'USPS',
    aliases: ['usps', 'postal', 'l01'],
  },
  dhl: {
    displayName: 'DHL',
    aliases: ['dhl'],
  },
}

export const normalizeCarrierCode = (value?: string | null): SupportedCarrierId | null => {
  if (!value) {
    return null
  }

  const normalizedValue = value.trim().toLowerCase()

  for (const [carrierId, metadata] of Object.entries(carrierRegistry) as Array<
    [SupportedCarrierId, (typeof carrierRegistry)[SupportedCarrierId]]
  >) {
    if (metadata.aliases.some((alias) => normalizedValue === alias || normalizedValue.includes(alias))) {
      return carrierId
    }
  }

  return null
}

export const formatCarrierName = (value?: string | null) => {
  const normalized = normalizeCarrierCode(value)

  if (normalized) {
    return carrierRegistry[normalized].displayName
  }

  return value?.trim() || 'Not assigned'
}

export const isCarrierConnected = (
  carrierCode: string | null | undefined,
  activeCarrierCode: string | null | undefined
) => {
  const normalizedCarrier = normalizeCarrierCode(carrierCode)
  const normalizedActiveCarrier = normalizeCarrierCode(activeCarrierCode)

  return Boolean(normalizedCarrier && normalizedActiveCarrier && normalizedCarrier === normalizedActiveCarrier)
}

export const carrierEnvironmentOptions = ['SANDBOX', 'PRODUCTION'] as const

export type CarrierEnvironment = (typeof carrierEnvironmentOptions)[number]
