import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'

export default function JobsPage() {
  const [jobs, setJobs] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.jobs().then(setJobs).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="text-red-600">{error}</p>
  if (!jobs) return <p className="text-ink/60">Loading jobs…</p>

  return (
    <div>
      <h1 className="text-2xl font-semibold mb-6">Open positions</h1>
      <div className="grid gap-4 sm:grid-cols-2">
        {jobs.map((job) => (
          <Link key={job.id} to={`/jobs/${job.id}`}
                className="block rounded-xl bg-white p-5 shadow-sm ring-1 ring-ink-100 hover:shadow-md transition">
            <div className="flex items-start justify-between gap-2">
              <h2 className="font-semibold text-lg">{job.title}</h2>
              {job.fresherFriendly && (
                <span className="shrink-0 rounded-full bg-marigold-100 text-marigold px-2 py-0.5 text-xs font-medium">
                  fresher friendly
                </span>
              )}
            </div>
            <p className="text-sm text-ink/70 mt-2 line-clamp-2">{job.description}</p>
            <p className="text-sm font-medium text-marigold mt-3">{job.applicants} applicant(s) →</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
