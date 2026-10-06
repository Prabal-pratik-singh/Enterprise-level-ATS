import { Routes, Route } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import Header from './components/Header'
import Dashboard from './pages/Dashboard'
import JobsPage from './pages/JobsPage'
import CandidatesPage from './pages/CandidatesPage'
import AllCandidatesPage from './pages/AllCandidatesPage'
import FlowPage from './pages/FlowPage'

export default function App() {
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
          <Route path="/jobs/:jobId" element={<CandidatesPage />} />
        </Routes>
      </div>
    </div>
  )
}
