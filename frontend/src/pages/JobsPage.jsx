import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

export default function JobsPage() {
  const [jobs, setJobs] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.jobs().then(setJobs).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="text-rose-400">{error}</p>
  if (!jobs) return <p className="text-dim">Loading jobs…</p>

  return (
    <div>
      <h1 className="mb-6 text-2xl font-semibold tracking-tight">Open positions</h1>
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
            <p className="mt-3 text-sm font-medium text-cyan">{job.applicants} applicant(s) →</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
