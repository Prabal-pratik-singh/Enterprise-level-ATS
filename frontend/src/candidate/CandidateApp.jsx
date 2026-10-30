import { Link, Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { Rocket, LogOut } from 'lucide-react'
import { logout, useSession } from '../auth'
import AuthForm from './AuthForm'
import VerifyPage from './VerifyPage'
import CandidateDashboard from './CandidateDashboard'
import ResumePage from './ResumePage'

/** The candidate portal (/candidate/*): its own top bar instead of the recruiter sidebar, same space theme. */
export default function CandidateApp() {
  const { status, user } = useSession()
  const candidate = status === 'signed-in' && user.role === 'CANDIDATE' ? user : null

  return (
    <div className="min-h-screen px-4 py-4 sm:px-6">
      <TopBar candidate={candidate} />
      <main className="mx-auto mt-6 w-full max-w-3xl pb-10">
        <Routes>
          <Route path="/candidate/verify" element={<VerifyPage />} />
          <Route path="/candidate/signup" element={candidate ? <Navigate to="/candidate" replace /> : <AuthForm mode="signup" />} />
          <Route path="/candidate/login" element={candidate ? <Navigate to="/candidate" replace /> : <AuthForm mode="login" />} />
          <Route path="/candidate" element={home(status, user)} />
          <Route path="/candidate/resume" element={candidate ? <ResumePage /> : home(status, user)} />
          <Route path="*" element={<Navigate to="/candidate" replace />} />
        </Routes>
      </main>
    </div>
  )
}

function home(status, user) {
  if (status !== 'signed-in') return <Navigate to="/candidate/login" replace />
  if (user.role === 'CANDIDATE') return <CandidateDashboard user={user} />
  return ( // a recruiter (or admin) who opened the candidate portal
    <div className="card card-strong mx-auto max-w-md space-y-3 p-6 text-center !rounded-2xl">
      <p className="text-sm">You are signed in as a recruiter. This portal is for candidates.</p>
      <Link to="/" className="text-sm text-cyan hover:underline">Open the recruiter console →</Link>
    </div>
  )
}

function TopBar({ candidate }) {
  return (
    <header className="card mx-auto flex w-full max-w-3xl flex-wrap items-center gap-3 px-4 py-2.5 !rounded-2xl">
      <Link to="/candidate" className="flex items-center gap-2.5">
        <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-gradient-to-br from-violet to-cyan shadow-lg shadow-violet/30">
          <Rocket size={16} className="text-white" />
        </div>
        <div className="leading-tight">
          <div className="text-sm font-semibold tracking-tight">ATS<span className="text-marigold">Pipeline</span></div>
          <div className="text-[10px] uppercase tracking-widest text-dim">candidate portal</div>
        </div>
      </Link>
      {candidate && (
        <nav className="flex items-center gap-1 text-sm">
          {[['/candidate', 'Dashboard'], ['/candidate/resume', 'My Resume']].map(([to, label]) => (
            <NavLink key={to} to={to} end
                     className={({ isActive }) => `rounded-lg px-2.5 py-1 transition ${isActive
                       ? 'bg-white/10 text-ink' : 'text-dim hover:text-ink'}`}>
              {label}
            </NavLink>
          ))}
        </nav>
      )}
      <div className="ml-auto flex min-w-0 items-center gap-3 text-sm">
        {candidate ? (
          <>
            <span className="hidden truncate text-dim md:inline" title={candidate.email}>{candidate.email}</span>
            <button onClick={logout} className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-dim hover:bg-white/5 hover:text-ink">
              <LogOut size={14} /> Sign out
            </button>
          </>
        ) : (
          <>
            <Link to="/candidate/login" className="text-dim hover:text-ink">Sign in</Link>
            <Link to="/candidate/signup" className="rounded-lg bg-white/10 px-2.5 py-1 hover:bg-white/15">Create account</Link>
          </>
        )}
      </div>
    </header>
  )
}
