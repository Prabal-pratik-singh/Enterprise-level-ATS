import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Plus, TriangleAlert } from 'lucide-react'
import { api } from '../api'
import NewJobModal from '../components/NewJobModal'

export default function JobsPage() {
  const [jobs, setJobs] = useState(null)
  const [error, setError] = useState(null)
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [creating, setCreating] = useState(params.get('new') === '1') // /jobs?new=1 opens the form
  const [notice, setNotice] = useState(null) // { title, unknownSkills } after a create

  useEffect(() => {
    api.jobs().then(setJobs).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="text-rose-400">{error}</p>
  if (!jobs) return <p className="text-dim">Loading jobs…</p>

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Open positions</h1>
        <button onClick={() => setCreating(true)}
                className="flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30">
          <Plus size={15} /> New job
        </button>
      </div>
      {notice && (
        <div className="mb-4 flex items-start gap-2 rounded-xl bg-emerald-400/10 p-3 text-sm text-emerald-200 ring-1 ring-emerald-400/30">
          <span>Created <b>{notice.title}</b> — it's open for applications.</span>
          {notice.unknownSkills.length > 0 && (
            <span className="ml-2 flex items-center gap-1 text-amber-300">
              <TriangleAlert size={14} /> not in the skill dictionary: {notice.unknownSkills.join(', ')} — check the spelling
            </span>
          )}
          <button onClick={() => setNotice(null)} className="ml-auto text-dim hover:text-ink">✕</button>
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        {jobs.map((job) => (
          <Link key={job.id} to={`/jobs/${job.id}`} className="card block p-5">
            <div className="flex items-start justify-between gap-2">
              <h2 className="text-lg font-semibold">{job.title}</h2>
              {job.fresherFriendly && (
                <span className="shrink-0 rounded-full bg-marigold/15 px-2 py-0.5 text-xs font-medium text-marigold ring-1 ring-marigold/30">
                  fresher friendly
                </span>
              )}
            </div>
            <p className="mt-2 line-clamp-2 text-sm text-dim">{job.description}</p>
            <div className="mt-3 flex items-center justify-between">
              <p className="text-sm font-medium text-cyan">{job.applicants} applicant(s) →</p>
              {/* a Link inside a Link is invalid HTML, so this one navigates by hand */}
              <span role="link" tabIndex={0}
                    onClick={(e) => { e.preventDefault(); e.stopPropagation(); navigate(`/upload?job=${job.id}`) }}
                    className="rounded-lg bg-white/5 px-2.5 py-1 text-xs text-dim ring-1 ring-white/10 hover:text-ink">
                Upload resumes
              </span>
            </div>
          </Link>
        ))}
      </div>
      {creating && (
        <NewJobModal onClose={() => setCreating(false)}
                     onCreated={({ job, unknownSkills }) => {
                       setJobs((list) => [...list, job])
                       setNotice({ title: job.title, unknownSkills })
                       setCreating(false)
                     }} />
      )}
    </div>
  )
}
