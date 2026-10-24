import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api } from '../api'
import {
  LayoutDashboard, Briefcase, Users, Workflow, ShieldAlert, Search, Activity,
  Database, Rocket, UploadCloud,
} from 'lucide-react'
import Avatar from './Avatar'
import { displayName, useSession } from '../auth'

// Real pages navigate; future phases show a "soon" tag; infra tools open the
// REAL consoles (Kafka UI, MinIO) — a demo that shows its own engine room.
const NAV = [
  { to: '/', icon: LayoutDashboard, label: 'Dashboard', end: true },
  { to: '/jobs', icon: Briefcase, label: 'Jobs' },
  { to: '/upload', icon: UploadCloud, label: 'Upload resumes' },
  { to: '/candidates', icon: Users, label: 'Candidates' },
  { to: '/flow', icon: Workflow, label: 'Live Flow' },
  { to: '/review', icon: ShieldAlert, label: 'Review Queue', badge: 'review' },
  { to: '/search', icon: Search, label: 'Talent Search' },
]

const INFRA = [
  { href: 'http://localhost:8085', icon: Activity, label: 'Kafka UI' },
  { href: 'http://localhost:9001', icon: Database, label: 'MinIO Console' },
]

const itemBase = 'flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition-colors'

export default function Sidebar() {
  const { user } = useSession()
  // waiting fraud verdicts — a small poll keeps the badge honest on every page
  const [reviewCount, setReviewCount] = useState(0)
  useEffect(() => {
    const tick = () => api.reviewQueue().then((q) => setReviewCount(q.length)).catch(() => {})
    tick()
    const id = setInterval(tick, 15000)
    return () => clearInterval(id)
  }, [])

  return (
    // overflow-y-auto: on a short window the sidebar scrolls instead of the bottom card overlapping the links
    <aside className="card card-strong fixed left-4 top-4 bottom-4 z-10 flex w-56 flex-col overflow-y-auto p-4 !rounded-2xl">
      {/* logo */}
      <div className="flex items-center gap-2.5 px-2 pb-5 pt-1">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-violet to-cyan shadow-lg shadow-violet/30">
          <Rocket size={18} className="text-white" />
        </div>
        <div className="leading-tight">
          <div className="font-semibold tracking-tight">ATS<span className="text-marigold">Pipeline</span></div>
          <div className="text-[10px] uppercase tracking-widest text-dim">recruiter console</div>
        </div>
      </div>

      {/* navigation */}
      <nav className="flex flex-col gap-1">
        {NAV.map(({ to, icon: Icon, label, soon, end, badge }) =>
          to ? (
            <NavLink key={label} to={to} end={end}
              className={({ isActive }) =>
                `${itemBase} ${isActive
                  ? 'bg-gradient-to-r from-violet/30 to-cyan/15 text-ink ring-1 ring-violet/40 shadow-[0_0_18px_rgba(139,92,246,0.25)]'
                  : 'text-dim hover:text-ink hover:bg-white/5'}`}>
              <Icon size={16} />
              {label}
              {badge === 'review' && reviewCount > 0 && (
                <span className="ml-auto rounded-full bg-amber-400/15 px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-amber-300 ring-1 ring-amber-400/30">
                  {reviewCount}
                </span>
              )}
            </NavLink>
          ) : (
            <div key={label} className={`${itemBase} cursor-default text-dim/60`}>
              <Icon size={16} />
              {label}
              <span className="ml-auto rounded-full bg-white/5 px-1.5 py-0.5 text-[9px] uppercase tracking-wider text-dim/80 ring-1 ring-white/10">
                {soon}
              </span>
            </div>
          ),
        )}
      </nav>

      <div className="mx-2 my-4 h-px bg-white/10" />

      {/* the engine room — real infra consoles */}
      <div className="px-2 pb-1 text-[10px] uppercase tracking-widest text-dim/70">Engine room</div>
      <nav className="mb-4 flex flex-col gap-1">
        {INFRA.map(({ href, icon: Icon, label }) => (
          <a key={label} href={href} target="_blank" rel="noreferrer"
             className={`${itemBase} text-dim hover:text-ink hover:bg-white/5`}>
            <Icon size={16} />
            {label}
            <span className="ml-auto text-[10px] text-dim/60">↗</span>
          </a>
        ))}
      </nav>

      {/* the signed-in recruiter, pinned to the bottom */}
      <div className="mt-auto shrink-0 rounded-xl bg-white/5 p-3 ring-1 ring-white/10">
        <div className="flex items-center gap-2.5">
          <Avatar name={displayName(user?.email)} size={34} />
          <div className="min-w-0 leading-tight">
            <div className="truncate text-sm font-medium">{displayName(user?.email)}</div>
            <div className="truncate text-[11px] text-dim" title={user?.email}>{user?.email}</div>
          </div>
        </div>
      </div>
    </aside>
  )
}
