import { useEffect } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import Header from './components/Header'
import Dashboard from './pages/Dashboard'
import JobsPage from './pages/JobsPage'
import CandidatesPage from './pages/CandidatesPage'
import AllCandidatesPage from './pages/AllCandidatesPage'
import FlowPage from './pages/FlowPage'
import ReviewQueuePage from './pages/ReviewQueuePage'
import SearchPage from './pages/SearchPage'
import UploadPage from './pages/UploadPage'
import LoginPage from './pages/LoginPage'
import CandidateApp from './candidate/CandidateApp'
import { refreshSession, useSession } from './auth'

export default function App() {
  const session = useSession()
  const { pathname } = useLocation()
  // a reload or a new tab starts with no token in memory — the refresh cookie brings the session back
  useEffect(() => { refreshSession() }, [])

  if (session.status === 'checking') {
    return <div className="flex min-h-screen items-center justify-center text-sm text-dim">Checking your session…</div>
  }
  // the candidate portal has its own pages, signed in or not ("/candidates" is the recruiter's list)
  if (pathname === '/candidate' || pathname.startsWith('/candidate/')) return <CandidateApp />
  if (session.status === 'signed-out') return <LoginPage />
  if (session.user.role === 'CANDIDATE') return <Navigate to="/candidate" replace />

  return (
    <div className="min-h-screen">
      <Sidebar />
      {/* content fills from the fixed sidebar (w-56 + left-4 + gap) to the right edge —
          dashboards want width, not a centered article column */}
      <div className="pl-[16.5rem] pr-6 pt-4 pb-10">
        <Header />
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/candidates" element={<AllCandidatesPage />} />
          <Route path="/flow" element={<FlowPage />} />
          <Route path="/review" element={<ReviewQueuePage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/jobs/:jobId" element={<CandidatesPage />} />
        </Routes>
      </div>
    </div>
  )
}
