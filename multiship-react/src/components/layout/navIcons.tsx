import type { ReactNode } from 'react'

/** One icon set shared by the sidebar and the topbar breadcrumb. */
export const navIcons: Record<string, ReactNode> = {
  dashboard: (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M3 3h6v6H3V3Zm8 0h6v4h-6V3ZM3 11h4v6H3v-6Zm6 0h8v6H9v-6Z" />
    </svg>
  ),
  orders: (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M4 4h12v3H4V4Zm0 4.5h12V16H4V8.5Zm2 1.5v1.5h4V10H6Z" />
    </svg>
  ),
  clients: (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M7 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7 1a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM1.5 16.2c0-2.9 2.5-5.2 5.5-5.2s5.5 2.3 5.5 5.2c0 .4-.3.8-.8.8H2.3a.8.8 0 0 1-.8-.8Zm12.7.8c.2-.4.3-.9.3-1.4 0-1.6-.7-3-1.8-4a4.6 4.6 0 0 1 5.8 4.3c0 .6-.5 1.1-1.1 1.1h-3.2Z" />
    </svg>
  ),
  carrier: (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M10 2.5 3 6v8l7 3.5L17 14V6l-7-3.5Zm0 1.12L14.66 6 10 8.34 5.34 6 10 3.62ZM4.5 7.2l4.75 2.37v6.01L4.5 13.2v-6Zm6.25 8.38V9.57L15.5 7.2v6l-4.75 2.38Z" />
    </svg>
  ),
  settings: (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-[18px] w-[18px]" aria-hidden="true">
      <path fillRule="evenodd" clipRule="evenodd" d="M8.4 2.5a.9.9 0 0 0-.87.68l-.28 1.12a5.9 5.9 0 0 0-1.2.7l-1.1-.4a.9.9 0 0 0-1.08.4l-1.1 1.9a.9.9 0 0 0 .2 1.14l.88.72a5.9 5.9 0 0 0 0 1.38l-.88.72a.9.9 0 0 0-.2 1.14l1.1 1.9a.9.9 0 0 0 1.08.4l1.1-.4c.37.29.78.52 1.2.7l.28 1.12a.9.9 0 0 0 .87.68h2.2a.9.9 0 0 0 .87-.68l.28-1.12c.43-.18.83-.41 1.2-.7l1.1.4a.9.9 0 0 0 1.08-.4l1.1-1.9a.9.9 0 0 0-.2-1.14l-.88-.72a5.9 5.9 0 0 0 0-1.38l.88-.72a.9.9 0 0 0 .2-1.14l-1.1-1.9a.9.9 0 0 0-1.08-.4l-1.1.4a5.9 5.9 0 0 0-1.2-.7l-.28-1.12a.9.9 0 0 0-.87-.68H8.4ZM10 12.75a2.75 2.75 0 1 1 0-5.5 2.75 2.75 0 0 1 0 5.5Z" />
    </svg>
  ),
  customs: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <circle cx="10" cy="10" r="7.25" />
      <path d="M2.75 10h14.5M10 2.75c1.8 1.9 2.8 4.5 2.8 7.25S11.8 15.35 10 17.25c-1.8-1.9-2.8-4.5-2.8-7.25S8.2 4.65 10 2.75Z" strokeLinecap="round" />
    </svg>
  ),
  service: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M2.75 6.5h9.5v7.5h-9.5z" strokeLinejoin="round" />
      <path d="M12.25 9h2.9l2.1 2.4v2.6h-5M5 16.25a1.75 1.75 0 1 0 0-3.5 1.75 1.75 0 0 0 0 3.5Zm9.5 0a1.75 1.75 0 1 0 0-3.5 1.75 1.75 0 0 0 0 3.5Z" strokeLinejoin="round" />
      <path d="M2.75 4h5M1.5 8.75h3" strokeLinecap="round" />
    </svg>
  ),
  package: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M10 2.75 3.25 6v8L10 17.25 16.75 14V6L10 2.75Z" strokeLinejoin="round" />
      <path d="M3.25 6 10 9.25 16.75 6M10 9.25v8M6.5 4.5l6.75 3.25" strokeLinejoin="round" />
    </svg>
  ),
  // Key — external API keys for the public shipping API.
  apiKey: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <circle cx="6.5" cy="13.5" r="3.75" />
      <path d="M9.25 10.75 16.5 3.5M13.5 6.5l2.5 2.5M11.5 8.5l1.75 1.75" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  // Open book — reference docs for the public shipping API.
  apiDocs: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M10 4.75C8.6 3.6 6.7 3 4.75 3c-.7 0-1.35.08-2 .25v11.5c.65-.17 1.3-.25 2-.25 1.95 0 3.85.6 5.25 1.75 1.4-1.15 3.3-1.75 5.25-1.75.7 0 1.35.08 2 .25V3.25c-.65-.17-1.3-.25-2-.25-1.95 0-3.85.6-5.25 1.75Z" strokeLinejoin="round" />
      <path d="M10 4.75v11.5" strokeLinecap="round" />
    </svg>
  ),
  // Chain-link — mapping ties an order's ship method to a carrier service.
  mapping: (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-[18px] w-[18px]" aria-hidden="true">
      <path d="M8.25 11.75 6 14a2.75 2.75 0 0 1-3.9-3.9l2.25-2.25a2.75 2.75 0 0 1 3.9 0" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M11.75 8.25 14 6a2.75 2.75 0 0 1 3.9 3.9l-2.25 2.25a2.75 2.75 0 0 1-3.9 0" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.75 12.25 12.25 7.75" strokeLinecap="round" />
    </svg>
  ),
}
