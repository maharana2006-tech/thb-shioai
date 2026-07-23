import ExternalApiReference from './ExternalApiReference'

/**
 * API Reference — its own Settings tab, next to API Keys. Static documentation
 * for the public shipping API (/api/v1/external); the content lives in
 * ExternalApiReference. Nothing to load, so it does not register a refresh
 * handler with the settings layout.
 */
export default function ApiReferencePage() {
  return (
    <div className="space-y-4 pb-16">
      <ExternalApiReference />
    </div>
  )
}
