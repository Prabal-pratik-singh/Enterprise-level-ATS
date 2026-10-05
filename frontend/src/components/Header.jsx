import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom'
// no Github icon: lucide removed brand icons — Code2 stands in for the repo link
import { Search, Bell, ChevronDown, Code2, HeartPulse, Activity, Database } from 'lucide-react'
import Avatar from './Avatar'

/** Small hook: close a popover when the user clicks anywhere outside it. */
function useClickOutside(onOutside) {
  const ref = useRef(null)
  useEffect(() => {
    const handler = (e) => {
      if (ref.current && !ref.current.contains(e.target)) onOutside()
    }
    document.addEventListener('pointerdown', handler)
    return () => document.removeEventListener('pointerdown', handler)
  }, [onOutside])
  return ref
}

export default function Header() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') || '')
  const [open, setOpen] = useState(null) // 'bell' | 'profile' | null
  const popRef = useClickOutside(() => setOpen(null))

  // Search filters candidate tables in place (?q=); from anywhere else it
  // lands on the all-candidates view with the query applied.
  const submit = (e) => {
    e.preventDefault()
    const query = q.trim()
    const base = location.pathname.startsWith('/jobs/') ? location.pathname : '/candidates'
    navigate(query ? `${base}?q=${encodeURIComponent(query)}` : base)
  }

  const menuItem = 'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-dim transition hover:bg-white/5 hover:text-ink'

  return (
    <header className="card sticky top-4 z-20 mb-6 flex items-center gap-4 px-4 py-2.5 !rounded-2xl" ref={popRef}>
      <form onSubmit={submit} className="flex min-w-0 flex-1 items-center gap-2 rounded-xl bg-white/5 px-3 py-2 ring-1 ring-white/10 transition focus-within:ring-cyan/50">
        <Search size={15} className="shrink-0 text-dim" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search candidates by name or email…"
          className="w-full bg-transparent text-sm outline-none placeholder:text-dim/70"
        />
      </form>

      {/* this dot is real: SSE keeps the tables current — click to watch the topics */}
      <a href="http://localhost:8085" target="_blank" rel="noreferrer"
         title="Open Kafka UI — the stream behind this dot"
         className="hidden items-center gap-1.5 rounded-full bg-white/5 px-2.5 py-1 text-[11px] text-dim ring-1 ring-white/10 transition hover:text-ink sm:flex">
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.9)]" />
        pipeline live
      </a>

      <div className="relative">
        <button onClick={() => setOpen(open === 'bell' ? null : 'bell')}
                className="rounded-xl p-2 text-dim transition hover:bg-white/5 hover:text-ink" title="Notifications">
          <Bell size={17} />
        </button>
        {open === 'bell' && (
          <div className="popover absolute right-0 top-11 w-72 p-4">
            <div className="mb-1 text-sm font-medium">Notifications</div>
            <p className="text-xs text-dim">
              Quiet for now — recruiter notifications ride the <code className="text-cyan">shortlist.updated</code> topic
              and arrive with Phase 8's rerank flow.
            </p>
          </div>
        )}
      </div>

      <div className="relative">
        <button onClick={() => setOpen(open === 'profile' ? null : 'profile')}
                className="flex items-center gap-2.5 rounded-xl px-1.5 py-1 transition hover:bg-white/5">
          <Avatar name="Demo Recruiter" size={32} />
          <div className="hidden leading-tight text-left md:block">
            <div className="text-sm font-medium">Demo Recruiter</div>
            <div className="text-[11px] text-dim">Recruiter</div>
          </div>
          <ChevronDown size={14} className={`text-dim transition ${open === 'profile' ? 'rotate-180' : ''}`} />
        </button>
        {open === 'profile' && (
          <div className="popover absolute right-0 top-12 w-60 p-2">
            <div className="px-3 py-2 text-xs text-dim">
              Single-recruiter demo — no auth by design (see README).
            </div>
            <a className={menuItem} href="https://github.com/Prabal-pratik-singh/Enterprise-level-ATS" target="_blank" rel="noreferrer">
              <Code2 size={15} /> Project on GitHub
            </a>
            <a className={menuItem} href="http://localhost:8082/actuator/health" target="_blank" rel="noreferrer">
              <HeartPulse size={15} /> API health
            </a>
            <a className={menuItem} href="http://localhost:8085" target="_blank" rel="noreferrer">
              <Activity size={15} /> Kafka UI
            </a>
            <a className={menuItem} href="http://localhost:9001" target="_blank" rel="noreferrer">
              <Database size={15} /> MinIO console
            </a>
          </div>
        )}
      </div>
    </header>
  )
}
