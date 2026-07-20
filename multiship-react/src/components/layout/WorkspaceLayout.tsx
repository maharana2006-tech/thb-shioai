import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import WorkspaceHeader from './WorkspaceHeader'
import BrandBackdrop from './BrandBackdrop'

const PIN_KEY = 'multiship_nav_pinned'

/**
 * App frame: full-height navy sidebar on the left (collapsible, pin state
 * persisted), and a content column beside it holding the light topbar and
 * the page. Print hides all chrome and resets the offset so 4x6 labels
 * stay full-bleed.
 */
export default function WorkspaceLayout() {
  const [pinned, setPinned] = useState(() => localStorage.getItem(PIN_KEY) === '1')

  const togglePin = () => {
    setPinned((cur) => {
      localStorage.setItem(PIN_KEY, cur ? '0' : '1')
      return !cur
    })
  }

  return (
    <div className="relative min-h-screen bg-[var(--color-background)] text-[var(--color-text)]">
      {/* whisper-subtle signature texture behind every page (hidden in print) */}
      <div className="fixed inset-0 print:hidden">
        <BrandBackdrop variant="light" />
      </div>
      <Sidebar pinned={pinned} onTogglePin={togglePin} />
      <div className={`relative transition-[margin] duration-200 ease-out print:ml-0 ${pinned ? 'ml-56' : 'ml-16'}`}>
        <WorkspaceHeader />
        <main className="px-4 py-5 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
