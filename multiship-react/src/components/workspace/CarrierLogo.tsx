import dhlLogo from '../../assets/carriers/dhl.svg'
import fedexLogo from '../../assets/carriers/fedex.svg'
import upsLogo from '../../assets/carriers/ups.svg'
import uspsLogo from '../../assets/carriers/usps.svg'
import { normalizeCarrierCode, type SupportedCarrierId } from '../../utils/carrierUtils'

const carrierLogos: Record<SupportedCarrierId, { src: string; alt: string }> = {
  fedex: { src: fedexLogo, alt: 'FedEx logo' },
  ups: { src: upsLogo, alt: 'UPS logo' },
  usps: { src: uspsLogo, alt: 'USPS logo' },
  dhl: { src: dhlLogo, alt: 'DHL logo' },
}

type CarrierLogoProps = {
  carrierId: string
  size?: number
  className?: string
}

export default function CarrierLogo({
  carrierId,
  size = 32,
  className = '',
}: CarrierLogoProps) {
  const normalizedCarrier = normalizeCarrierCode(carrierId) || 'fedex'
  const logo = carrierLogos[normalizedCarrier]

  return (
    <img
      src={logo.src}
      alt={logo.alt}
      className={`block shrink-0 object-contain ${className}`.trim()}
      style={{ height: `${size}px`, width: 'auto' }}
    />
  )
}
