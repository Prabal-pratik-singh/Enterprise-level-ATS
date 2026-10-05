import { Routes, Route, Link } from 'react-router-dom'
import JobsPage from './pages/JobsPage'
import CandidatesPage from './pages/CandidatesPage'

export default function App() {
  return (
    <div className="min-h-screen">
      <header className="bg-ink text-white shadow">
        <div className="mx-auto max-w-6xl px-6 py-4 flex items-baseline gap-3">
          <Link to="/" className="text-xl font-semibold tracking-tight">
            ATS<span className="text-marigold">Pipeline</span>
          </Link>
          <span className="text-xs text-white/50">event-driven screening demo</span>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Routes>
          <Route path="/" element={<JobsPage />} />
          <Route path="/jobs/:jobId" element={<CandidatesPage />} />
        </Routes>
      </main>
    </div>
  )
}
