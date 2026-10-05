import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'
import CandidateTable from '../components/CandidateTable'
import CandidateDrawer from '../components/CandidateDrawer'

/**
 * Every candidate across every job, in one ranked table — the landing spot for
 * the dashboard's KPI cards. ?f= picks a filter (chips below), ?q= comes from
 * the header search. Live via one SSE stream per job.
 */
const FILTERS = {
  all: { label: 'All', fn: () => true },
  scored: { label: 'Scored', fn: (r) => r.finalScore != null },
  strong: { label: 'Strong (80+)', fn: (r) => (r.finalScore ?? 0) >= 80 },
  awaiting: { label: 'Awaiting decision', fn: (r) => r.status === 'SCORED' },
  knocked: { label: 'Knocked out', fn: (r) => r.knockedOut },
}

export default function AllCandidatesPage() {
  const [params, setParams] = useSearchParams()
  const filterKey = params.get('f') || 'all'
  const query = (params.get('q') || '').toLowerCase()

  const [rows, setRows] = useState([])
  const [jobs, setJobs] = useState([])
  const [selected, setSelected] = useState(null)
  const [freshIds, setFreshIds] = useState(new Set())
  const [error, setError] = useState(null)

  useEffect(() => {
    api.jobs()
      .then(async (jobList) => {
        setJobs(jobList)
        const pages = await Promise.all(
          jobList.map((job) => api.candidates(job.id, 0, 200)
            .then((d) => d.items.map((row) => ({ ...row, jobId: job.id, jobTitle: job.title })))),
        )
        setRows(pages.flat().sort((a, b) => (b.finalScore ?? -1) - (a.finalScore ?? -1)))
      })
      .catch((e) => setError(e.message))
  }, [])

  // one SSE stream per job keeps the merged table live too
  useEffect(() => {
    if (jobs.length === 0) return undefined
    const sources = jobs.map((job) => {
      const source = new EventSource(`/api/jobs/${job.id}/events`)
      source.addEventListener('candidate', (event) => {
        const row = { ...JSON.parse(event.data), jobId: job.id, jobTitle: job.title }
        setRows((current) => {
          const others = current.filter((r) => r.applicationId !== row.applicationId)
          const next = [...others, row]
          next.sort((a, b) => (b.finalScore ?? -1) - (a.finalScore ?? -1))
          return next
        })
        setFreshIds((ids) => new Set(ids).add(row.applicationId))
        setTimeout(() => setFreshIds((ids) => {
          const copy = new Set(ids); copy.delete(row.applicationId); return copy
        }), 3000)
      })
      return source
    })
    return () => sources.forEach((s) => s.close())
  }, [jobs])

  const updateRowStatus = (applicationId, status) =>
    setRows((current) => current.map((r) => (r.applicationId === applicationId ? { ...r, status } : r)))

  const visible = useMemo(() => {
    // generic status filter from the pipeline board: f=status:SHORTLISTED etc.
    const statusKey = filterKey.startsWith('status:') ? filterKey.slice(7) : null
    const byFilter = statusKey
      ? rows.filter((r) => r.status === statusKey)
      : rows.filter(FILTERS[filterKey]?.fn || FILTERS.all.fn)
    if (!query) return byFilter
    return byFilter.filter((r) =>
      r.name?.toLowerCase().includes(query) || r.email?.toLowerCase().includes(query))
  }, [rows, filterKey, query])

  if (error) return <p className="text-rose-400">{error}</p>

  const statusKey = filterKey.startsWith('status:') ? filterKey.slice(7) : null

  return (
    <div>
      <div className="mb-4 flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">
          All candidates <span className="text-base text-dim">({visible.length} of {rows.length})</span>
        </h1>
      </div>

      {/* filter chips — URL-driven so KPI cards and pipeline columns can deep-link */}
      <div className="mb-5 flex flex-wrap gap-2">
        {Object.entries(FILTERS).map(([key, f]) => (
          <button key={key}
                  onClick={() => setParams(key === 'all' ? {} : { f: key })}
                  className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition ${
                    filterKey === key
                      ? 'bg-gradient-to-r from-violet/40 to-cyan/25 text-ink ring-violet/50 shadow-[0_0_14px_rgba(139,92,246,0.3)]'
                      : 'bg-white/5 text-dim ring-white/10 hover:text-ink hover:bg-white/10'}`}>
            {f.label}
          </button>
        ))}
        {statusKey && (
          <span className="rounded-full bg-gradient-to-r from-violet/40 to-cyan/25 px-3 py-1 text-xs font-medium text-ink ring-1 ring-violet/50">
            status: {statusKey.toLowerCase().replaceAll('_', ' ')}
            <button className="ml-2 opacity-70 hover:opacity-100" onClick={() => setParams({})}>✕</button>
          </span>
        )}
      </div>

      <CandidateTable rows={visible} freshIds={freshIds} onSelect={setSelected} showJob
                      emptyMessage="No candidates match this filter." />

      {selected && (
        <CandidateDrawer applicationId={selected}
                         onClose={() => setSelected(null)}
                         onDecision={updateRowStatus} />
      )}
    </div>
  )
}
