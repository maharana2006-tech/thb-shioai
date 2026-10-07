import type { ReactNode } from 'react'
import { FiArrowRight, FiBox, FiShield, FiZap } from 'react-icons/fi'
import BrandBackdrop from '../layout/BrandBackdrop'

interface AuthLayoutProps {
  eyebrow: string
  title: string
  description: string
  children: ReactNode
  footer?: ReactNode
}

/**
 * Auth architecture v3 — "sign your waybill".
 * Instead of the usual split-screen or centered card, the whole screen is ONE
 * artifact: a printed MultiShip operator access document floating on the
 * espresso night backdrop. The form fields ARE fields on the paper.
 *  · left tear-off stub (punch hole, vertical legend, ladder barcode)
 *  · espresso document header band + waybill meta cells
 *  · faded rubber stamp, barcode footer, torn receipt bottom edge
 * Same props API as ever, so Login/Signup need no changes.
 */

// pseudo-Code128 bar widths — purely decorative
const BARCODE = [3, 1, 2, 1, 4, 1, 1, 3, 2, 1, 2, 4, 1, 2, 1, 3, 1, 1, 2, 3, 1, 4, 2, 1, 3, 1, 2, 1, 1, 3, 2, 1, 4, 1, 2, 2]

const PAPER = '#faf9f7'
// midpoint of the backdrop gradient — used for the perforation punch notches
const NIGHT = '#1a1007'

export default function AuthLayout({ eyebrow, title, description, children, footer }: AuthLayoutProps) {
  const printedOn = new Date().toISOString().slice(0, 10)

  return (
    <div className="relative min-h-screen overflow-hidden bg-[#160e07]">
      <BrandBackdrop variant="dark" />

      {/* ghost wordmark bleeding off the bottom of the night sky */}
      <p
        aria-hidden="true"
        className="pointer-events-none absolute -bottom-[4vw] left-1/2 -translate-x-1/2 select-none whitespace-nowrap text-[17vw] font-black uppercase tracking-tighter text-[#e1dcc9]/[0.035]"
      >
        MultiShip
      </p>

      <div className="relative flex min-h-screen flex-col px-4 py-5 sm:px-8">
        {/* top chrome: logo + trust line */}
        <header className="flex items-center justify-between auth-fade-up">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-[#e1dcc9] text-[#1f150c] shadow-lg">
              <FiBox className="h-5 w-5" />
            </div>
            <div>
              <p className="text-[15px] font-bold leading-tight text-white">MultiShip</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-[#e1dcc9]/60">
                Shipping Operations
              </p>
            </div>
          </div>
          <div className="hidden items-center gap-5 text-[11px] font-semibold text-[#e1dcc9]/55 md:flex">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" /> Platform healthy
            </span>
            <span className="inline-flex items-center gap-1.5">
              <FiShield className="h-3.5 w-3.5" /> Role-scoped access
            </span>
            <span className="inline-flex items-center gap-1.5">
              <FiZap className="h-3.5 w-3.5" /> UPS · FedEx · USPS
            </span>
          </div>
        </header>

        {/* ===== the document ===== */}
        <main className="flex flex-1 items-center justify-center py-8">
          <div className="w-full max-w-[640px] auth-fade-up auth-fade-up-delay-1">
            <div className="grid grid-cols-1 shadow-[0_36px_90px_rgba(0,0,0,0.55)] md:grid-cols-[6.5rem_minmax(0,1fr)]">
              {/* --- tear-off stub --- */}
              <aside
                className="relative hidden flex-col items-center rounded-tl-2xl border-r border-dashed border-slate-300 py-5 md:flex"
                style={{ backgroundColor: PAPER }}
              >
                {/* perforation punch notches */}
                <span aria-hidden="true" className="absolute -right-[9px] -top-[9px] h-[18px] w-[18px] rounded-full" style={{ backgroundColor: NIGHT }} />
                <span aria-hidden="true" className="absolute -bottom-[9px] -right-[9px] h-[18px] w-[18px] rounded-full" style={{ backgroundColor: NIGHT }} />

                {/* luggage-tag punch hole */}
                <span aria-hidden="true" className="h-4 w-4 rounded-full border-[3px] border-slate-300" style={{ backgroundColor: NIGHT }} />

                <p
                  className="my-6 text-[9.5px] font-bold uppercase tracking-[0.32em] text-slate-400"
                  style={{ writingMode: 'vertical-rl' }}
                >
                  Operator copy · Authorized personnel only
                </p>

                {/* ladder barcode */}
                <div aria-hidden="true" className="mt-auto flex w-9 flex-col items-stretch gap-[2px]">
                  {BARCODE.slice(0, 24).map((w, i) => (
                    <span key={i} className="bg-slate-800/80" style={{ height: `${w}px` }} />
                  ))}
                </div>
                <p className="mt-2 font-mono text-[8.5px] tracking-widest text-slate-400">MS-2481</p>
              </aside>

              {/* --- document body --- */}
              <div style={{ backgroundColor: PAPER }} className="rounded-t-2xl md:rounded-tl-none">
                {/* header band */}
                <div className="flex items-center justify-between gap-3 rounded-t-2xl bg-[#1f150c] px-5 py-3 sm:px-7 md:rounded-tl-none">
                  <p className="text-[10px] font-black uppercase tracking-[0.22em] text-[#e1dcc9]">
                    Operator access document
                  </p>
                  <span className="rounded bg-[#e1dcc9]/15 px-2 py-1 text-[9px] font-bold uppercase tracking-[0.18em] text-[#e1dcc9]">
                    {eyebrow}
                  </span>
                </div>

                {/* waybill meta cells */}
                <div className="grid grid-cols-3 divide-x divide-slate-200 border-b border-slate-200">
                  <div className="px-4 py-2.5 sm:px-5">
                    <p className="text-[8.5px] font-bold uppercase tracking-[0.18em] text-slate-400">Form</p>
                    <p className="mt-0.5 font-mono text-[11px] font-semibold text-slate-800">MS-01 / OPS</p>
                  </div>
                  <div className="px-4 py-2.5 sm:px-5">
                    <p className="text-[8.5px] font-bold uppercase tracking-[0.18em] text-slate-400">Route</p>
                    <p className="mt-0.5 flex items-center gap-1 font-mono text-[11px] font-semibold text-slate-800">
                      YOU <FiArrowRight className="h-3 w-3 text-[#412d15]" /> WORKSPACE
                    </p>
                  </div>
                  <div className="px-4 py-2.5 sm:px-5">
                    <p className="text-[8.5px] font-bold uppercase tracking-[0.18em] text-slate-400">Printed</p>
                    <p className="mt-0.5 font-mono text-[11px] font-semibold text-slate-800">{printedOn}</p>
                  </div>
                </div>

                {/* content */}
                <div className="relative px-5 py-6 sm:px-7">
                  {/* faded rubber stamp */}
                  <div
                    aria-hidden="true"
                    className="pointer-events-none absolute right-4 top-4 rotate-[7deg] rounded-md border-[2.5px] border-[#7a3b2e]/45 px-2.5 py-1 text-center sm:right-7"
                  >
                    <p className="text-[10px] font-black uppercase tracking-[0.26em] text-[#7a3b2e]/55">Cleared</p>
                    <p className="text-[6.5px] font-bold uppercase tracking-[0.18em] text-[#7a3b2e]/45">for access · MS ops</p>
                  </div>

                  <h1 className="max-w-[26rem] pr-24 text-[22px] font-semibold leading-snug tracking-tight text-slate-950 sm:pr-28">
                    {title}
                  </h1>
                  <p className="mt-2 max-w-md text-[12.5px] leading-5.5 text-slate-500">{description}</p>

                  <div className="mt-6">{children}</div>

                  {footer ? (
                    <div className="mt-6 border-t border-dashed border-slate-300 pt-4">{footer}</div>
                  ) : null}
                </div>

                {/* barcode footer */}
                <div className="flex items-end justify-between gap-4 border-t border-dashed border-slate-300 px-5 py-4 sm:px-7">
                  <div>
                    <div aria-hidden="true" className="flex h-7 items-stretch gap-[2px]">
                      {BARCODE.map((w, i) => (
                        <span key={i} className="bg-slate-900" style={{ width: `${w}px` }} />
                      ))}
                    </div>
                    <p className="mt-1.5 font-mono text-[9.5px] tracking-[0.3em] text-slate-500">MS 2481 0718 OPS</p>
                  </div>
                  <p className="pb-0.5 text-right text-[8.5px] font-bold uppercase leading-relaxed tracking-[0.16em] text-slate-400">
                    Void if unsigned
                    <span className="block font-medium normal-case tracking-normal text-slate-400">
                      Credentials verified at the gate
                    </span>
                  </p>
                </div>
              </div>
            </div>

            {/* torn receipt bottom edge */}
            <div
              aria-hidden="true"
              className="h-2.5 w-full"
              style={{
                backgroundImage: `radial-gradient(circle at 7px 12px, rgba(0,0,0,0) 7px, ${PAPER} 7.5px)`,
                backgroundSize: '14px 14px',
                backgroundRepeat: 'repeat-x',
              }}
            />
          </div>
        </main>
      </div>
    </div>
  )
}
