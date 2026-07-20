/**
 * The MultiShip signature backdrop — a hand-drawn "shipping routes at night"
 * composition in the espresso/cream identity. Pure inline SVG: no image
 * assets, crisp at any resolution, themable by variant.
 *
 *  · deep espresso gradient with warm aurora glows
 *  · a halftone dot-globe rising from the lower left
 *  · dashed great-circle routes connecting parcel nodes (subtle drift)
 *  · a ghost parcel-cube watermark echoing the app logo
 *
 * variant "dark"  → auth screens (rich, atmospheric)
 * variant "light" → workspace canvas (near-invisible texture; print-hidden)
 */
export default function BrandBackdrop({ variant = 'dark' }: { variant?: 'dark' | 'light' }) {
  const dark = variant === 'dark'
  const ink = dark ? '#e1dcc9' : '#412d15'
  const dotOpacity = dark ? 0.16 : 0.05
  const routeOpacity = dark ? 0.35 : 0.1
  const nodeOpacity = dark ? 0.5 : 0.12

  // halftone globe: concentric dotted latitude arcs
  const arcs = [86, 118, 150, 182, 214, 246, 278]

  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden print:hidden">
      <svg className="h-full w-full" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice">
        <defs>
          {/* Light variant stays within ONE warm-neutral ramp (~hue 45°,
              2–3% saturation) so it never fights the white cards or the
              #f7f6f3 canvas — same temperature, one system. */}
          <radialGradient id="mbAurora1" cx="20%" cy="10%" r="60%">
            <stop offset="0%" stopColor="#c9a15c" stopOpacity={dark ? 0.28 : 0.045} />
            <stop offset="100%" stopColor="#c9a15c" stopOpacity="0" />
          </radialGradient>
          <radialGradient id="mbAurora2" cx="85%" cy="85%" r="55%">
            <stop offset="0%" stopColor="#8a5a2b" stopOpacity={dark ? 0.32 : 0.035} />
            <stop offset="100%" stopColor="#8a5a2b" stopOpacity="0" />
          </radialGradient>
          <linearGradient id="mbBase" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={dark ? '#160e07' : '#faf9f7'} />
            <stop offset="55%" stopColor={dark ? '#221507' : '#f7f6f3'} />
            <stop offset="100%" stopColor={dark ? '#100a05' : '#f3f2ee'} />
          </linearGradient>
          {/* one dot stamped into a repeating halftone field */}
          <pattern id="mbDots" width="26" height="26" patternUnits="userSpaceOnUse">
            <circle cx="2" cy="2" r="1.4" fill={ink} opacity={dotOpacity} />
          </pattern>
        </defs>

        {/* base + auroras */}
        <rect width="1440" height="900" fill="url(#mbBase)" />
        <rect width="1440" height="900" fill="url(#mbAurora1)" />
        <rect width="1440" height="900" fill="url(#mbAurora2)" />

        {/* faint halftone field across the upper sky */}
        <rect x="0" y="0" width="1440" height="420" fill="url(#mbDots)" opacity={dark ? 0.5 : 0.6} />

        {/* dot-globe rising from the lower left */}
        <g transform="translate(240 980)">
          {arcs.map((r) => (
            <circle
              key={r}
              r={r}
              fill="none"
              stroke={ink}
              strokeOpacity={dotOpacity * 1.6}
              strokeWidth="2"
              strokeDasharray="0.1 14"
              strokeLinecap="round"
            />
          ))}
          <circle r="330" fill="none" stroke={ink} strokeOpacity={dotOpacity * 2.2} strokeWidth="1.2" />
        </g>

        {/* great-circle routes with parcel nodes */}
        <g className="mb-drift" fill="none" strokeLinecap="round">
          <path
            d="M -40 620 C 260 430, 520 400, 760 470 S 1220 640, 1500 480"
            stroke={ink}
            strokeOpacity={routeOpacity}
            strokeWidth="1.6"
            strokeDasharray="2 10"
          />
          <path
            d="M -60 300 C 300 220, 640 180, 900 260 S 1330 420, 1520 320"
            stroke={ink}
            strokeOpacity={routeOpacity * 0.8}
            strokeWidth="1.4"
            strokeDasharray="2 12"
          />
          <path
            d="M 160 940 C 420 700, 780 640, 1050 700 S 1400 810, 1560 720"
            stroke={ink}
            strokeOpacity={routeOpacity * 0.65}
            strokeWidth="1.4"
            strokeDasharray="2 11"
          />
          {/* parcel nodes: ring + diamond, at route waypoints */}
          {[
            [270, 452],
            [760, 470],
            [1180, 610],
            [420, 210],
            [900, 260],
            [1290, 400],
          ].map(([x, y], i) => (
            <g key={i} transform={`translate(${x} ${y})`}>
              <circle r="9" stroke={ink} strokeOpacity={nodeOpacity * 0.55} strokeWidth="1" />
              <rect x="-3.2" y="-3.2" width="6.4" height="6.4" transform="rotate(45)" fill={ink} opacity={nodeOpacity} />
            </g>
          ))}
        </g>

        {/* ghost parcel-cube watermark (echo of the app logo), top right */}
        <g transform="translate(1180 90)" fill="none" stroke={ink} strokeOpacity={dark ? 0.12 : 0.05} strokeWidth="3" strokeLinejoin="round">
          <path d="M70 0 L140 38 L140 118 L70 156 L0 118 L0 38 Z" />
          <path d="M0 38 L70 76 L140 38 M70 76 L70 156" />
          <path d="M35 19 L105 57" strokeDasharray="4 8" />
        </g>

        {/* fine vignette to seat the content */}
        {dark ? <rect width="1440" height="900" fill="#0d0803" opacity="0.18" /> : null}
      </svg>
    </div>
  )
}
