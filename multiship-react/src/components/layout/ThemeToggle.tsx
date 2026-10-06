import { useSyncExternalStore } from 'react'
import { FiMonitor, FiMoon, FiSun } from 'react-icons/fi'
import { getThemePref, setThemePref, subscribeTheme, type ThemePref } from '../../utils/theme'

const OPTS: { value: ThemePref; label: string; Icon: typeof FiSun }[] = [
  { value: 'system', label: 'System', Icon: FiMonitor },
  { value: 'light', label: 'Light', Icon: FiSun },
  { value: 'dark', label: 'Dark', Icon: FiMoon },
]

/**
 * Light / dark / system theme control for the sidebar. Collapsed (icon rail):
 * one button that cycles the three modes. Expanded: a 3-way segmented control.
 * Styled for the always-dark espresso sidebar, so it uses fixed chrome colors.
 */
export default function ThemeToggle({ collapsed }: { collapsed: boolean }) {
  const pref = useSyncExternalStore(subscribeTheme, getThemePref, getThemePref)

  if (collapsed) {
    const idx = OPTS.findIndex((o) => o.value === pref)
    const cur = OPTS[idx < 0 ? 0 : idx]
    const next = OPTS[(idx + 1) % OPTS.length]
    const Icon = cur.Icon
    return (
      <div className="px-2.5 pb-1">
        <button
          type="button"
          onClick={() => setThemePref(next.value)}
          title={`Theme: ${cur.label} — switch to ${next.label}`}
          aria-label={`Theme: ${cur.label}. Switch to ${next.label}`}
          className="flex w-full items-center justify-center rounded-lg px-3 py-2 text-slate-400 transition hover:bg-white/[0.06] hover:text-slate-200"
        >
          <Icon className="h-[18px] w-[18px] shrink-0" />
        </button>
      </div>
    )
  }

  return (
    <div className="px-2.5 pb-1">
      <div role="radiogroup" aria-label="Theme" className="flex items-center gap-0.5 rounded-lg bg-white/[0.05] p-0.5">
        {OPTS.map(({ value, label, Icon }) => {
          const on = pref === value
          return (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={on}
              onClick={() => setThemePref(value)}
              title={label}
              className={`flex flex-1 items-center justify-center gap-1.5 rounded-md py-1.5 text-[11px] font-semibold transition ${
                on ? 'bg-[#e1dcc9] text-[#1f150c]' : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Icon className="h-3.5 w-3.5 shrink-0" />
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
