import { Routes, Route } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import Header from './components/Header'
import Dashboard from './pages/Dashboard'
import JobsPage from './pages/JobsPage'
import CandidatesPage from './pages/CandidatesPage'

export default function App() {
  return (
    <div className="min-h-screen">
      <Sidebar />
      {/* content clears the fixed sidebar (w-56 + left-4 + gap) */}
      <div className="pl-[16.5rem] pr-4 pt-4 pb-10">
        <div className="mx-auto max-w-6xl">
          <Header />
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/jobs/:jobId" element={<CandidatesPage />} />
          </Routes>
        </div>
      </div>
    </div>
  )
}
