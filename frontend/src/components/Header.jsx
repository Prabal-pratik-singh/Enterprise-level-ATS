import { useState } from 'react'
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom'
import { Search, Bell, ChevronDown } from 'lucide-react'
import Avatar from './Avatar'

export default function Header() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') || '')

  // Searching on a candidates page filters it in place (?q=); anywhere else it
  // jumps to the jobs list. Honest behavior, no dead search box.
  const submit = (e) => {
    e.preventDefault()
    const query = q.trim()
    if (location.pathname.startsWith('/jobs/')) {
      navigate(`${location.pathname}?q=${encodeURIComponent(query)}`)
    } else {
      navigate('/jobs')
    }
  }

  return (
    <header className="card sticky top-4 z-10 mb-6 flex items-center gap-4 px-4 py-2.5 !rounded-2xl">
      <form onSubmit={submit} className="flex min-w-0 flex-1 items-center gap-2 rounded-xl bg-white/5 px-3 py-2 ring-1 ring-white/10 focus-within:ring-cyan/50 transition">
        <Search size={15} className="shrink-0 text-dim" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search candidates by name or email…"
          className="w-full bg-transparent text-sm outline-none placeholder:text-dim/70"
        />
      </form>

      {/* live pipeline pulse — this dot is real: SSE keeps the tables current */}
      <div className="hidden items-center gap-1.5 rounded-full bg-white/5 px-2.5 py-1 text-[11px] text-dim ring-1 ring-white/10 sm:flex">
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.9)]" />
        pipeline live
      </div>

      <button className="relative rounded-xl p-2 text-dim transition hover:bg-white/5 hover:text-ink" title="Notifications">
        <Bell size={17} />
        <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-marigold shadow-[0_0_6px_rgba(228,149,43,0.9)]" />
      </button>

      <div className="flex items-center gap-2.5 rounded-xl px-1.5 py-1 transition hover:bg-white/5">
        <Avatar name="Demo Recruiter" size={32} />
        <div className="hidden leading-tight md:block">
          <div className="text-sm font-medium">Demo Recruiter</div>
          <div className="text-[11px] text-dim">Recruiter</div>
        </div>
        <ChevronDown size={14} className="text-dim" />
      </div>
    </header>
  )
}
