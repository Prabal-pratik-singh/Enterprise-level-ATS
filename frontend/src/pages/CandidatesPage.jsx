import { useCallback, useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { api } from '../api'
import ScoreBars from '../components/ScoreBars'
import StatusChip from '../components/StatusChip'
import CandidateDrawer from '../components/CandidateDrawer'

export default function CandidatesPage() {
  const { jobId } = useParams()
  const [rows, setRows] = useState([])
  const [total, setTotal] = useState(0)
  const [selected, setSelected] = useState(null)       // applicationId of the open drawer
  const [freshIds, setFreshIds] = useState(new Set())  // rows that just arrived via SSE
  const [error, setError] = useState(null)

  const load = useCallback(() => {
    api.candidates(jobId).then((data) => {
      setRows(data.items)
      setTotal(data.total)
    }).catch((e) => setError(e.message))
  }, [jobId])

  useEffect(load, [load])

  // Live updates: one EventSource per page visit. A scored candidate arrives
  // as a full row — upsert it, re-sort by score, flash-highlight for 3s.
  useEffect(() => {
    const source = new EventSource(`/api/jobs/${jobId}/events`)
    source.addEventListener('candidate', (event) => {
      const row = JSON.parse(event.data)
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
    return () => source.close() // leaving the page closes the stream
  }, [jobId])

  const updateRowStatus = (applicationId, status) =>
    setRows((current) => current.map((r) => (r.applicationId === applicationId ? { ...r, status } : r)))

  if (error) return <p className="text-red-600">{error}</p>

  return (
    <div>
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-semibold">
          Ranked candidates <span className="text-ink/50 text-base">({total})</span>
        </h1>
        <Link to="/" className="text-sm text-marigold hover:underline">← all jobs</Link>
      </div>

      <div className="overflow-hidden rounded-xl bg-white shadow-sm ring-1 ring-ink-100">
        <table className="w-full text-sm">
          <thead className="bg-ink text-white text-left">
            <tr>
              <th className="px-4 py-3 w-10">#</th>
              <th className="px-4 py-3">Candidate</th>
              <th className="px-4 py-3 w-20">Score</th>
              <th className="px-4 py-3 w-44">Breakdown</th>
              <th className="px-4 py-3 w-28">Experience</th>
              <th className="px-4 py-3 w-32">Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.applicationId}
                  onClick={() => setSelected(row.applicationId)}
                  className={`border-t border-ink-100 cursor-pointer hover:bg-marigold-100/40 transition
                              ${freshIds.has(row.applicationId) ? 'bg-marigold-100' : ''}`}>
                <td className="px-4 py-3 text-ink/50 tabular-nums">{index + 1}</td>
                <td className="px-4 py-3">
                  <div className="font-medium">{row.name}</div>
                  <div className="text-xs text-ink/50">{row.email}</div>
                </td>
                <td className="px-4 py-3">
                  <span className={`text-lg font-semibold tabular-nums ${row.knockedOut ? 'text-red-500' : ''}`}>
                    {row.finalScore != null ? Math.round(row.finalScore) : '—'}
                  </span>
                </td>
                <td className="px-4 py-3"><ScoreBars components={row.components} compact /></td>
                <td className="px-4 py-3 text-ink/70">
                  {row.totalExperienceMonths != null ? `${row.totalExperienceMonths} mo` : '—'}
                  {row.seniority && <span className="block text-xs text-ink/40">{row.seniority}</span>}
                </td>
                <td className="px-4 py-3"><StatusChip status={row.status} /></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan="6" className="px-4 py-10 text-center text-ink/50">
                No candidates yet — run <code className="text-marigold">./tools/seed.sh 5</code> and watch them appear live.
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {selected && (
        <CandidateDrawer applicationId={selected}
                         onClose={() => setSelected(null)}
                         onDecision={updateRowStatus} />
      )}
    </div>
  )
}
