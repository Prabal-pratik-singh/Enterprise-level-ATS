import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams, useSearchParams, Link } from 'react-router-dom'
import { api } from '../api'
import Avatar from '../components/Avatar'
import ScoreBars from '../components/ScoreBars'
import StatusChip from '../components/StatusChip'
import CandidateDrawer from '../components/CandidateDrawer'

export default function CandidatesPage() {
  const { jobId } = useParams()
  const [params] = useSearchParams()
  const query = (params.get('q') || '').toLowerCase()

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

  // header search lands here as ?q= — filter client-side by name/email
  const visible = useMemo(() => {
    if (!query) return rows
    return rows.filter((r) =>
      r.name?.toLowerCase().includes(query) || r.email?.toLowerCase().includes(query))
  }, [rows, query])

  if (error) return <p className="text-rose-400">{error}</p>

  return (
    <div>
      <div className="mb-6 flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">
          Ranked candidates <span className="text-base text-dim">({query ? `${visible.length} of ${total}` : total})</span>
        </h1>
        <Link to="/" className="text-sm text-cyan hover:underline">← dashboard</Link>
      </div>

      <div className="card overflow-hidden !rounded-2xl">
        <table className="w-full text-sm">
          <thead className="text-left text-[11px] uppercase tracking-wider text-dim">
            <tr className="border-b border-white/10 bg-white/5">
              <th className="px-4 py-3 w-10 font-medium">#</th>
              <th className="px-4 py-3 font-medium">Candidate</th>
              <th className="px-4 py-3 w-20 font-medium">Score</th>
              <th className="px-4 py-3 w-44 font-medium">Breakdown</th>
              <th className="px-4 py-3 w-28 font-medium">Experience</th>
              <th className="px-4 py-3 w-32 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((row, index) => (
              <tr key={row.applicationId}
                  onClick={() => setSelected(row.applicationId)}
                  className={`cursor-pointer border-t border-white/5 transition hover:bg-white/5
                              ${freshIds.has(row.applicationId) ? 'bg-marigold/10' : ''}`}>
                <td className="px-4 py-3 tabular-nums text-dim">{index + 1}</td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <Avatar name={row.name} size={32} />
                    <div className="leading-tight">
                      <div className="font-medium">{row.name}</div>
                      <div className="text-xs text-dim">{row.email}</div>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3">
                  <span className={`text-lg font-semibold tabular-nums ${row.knockedOut ? 'text-rose-400' : 'text-marigold'}`}>
                    {row.finalScore != null ? Math.round(row.finalScore) : '—'}
                  </span>
                </td>
                <td className="px-4 py-3"><ScoreBars components={row.components} compact /></td>
                <td className="px-4 py-3 text-ink/80">
                  {row.totalExperienceMonths != null ? `${row.totalExperienceMonths} mo` : '—'}
                  {row.seniority && <span className="block text-xs text-dim">{row.seniority}</span>}
                </td>
                <td className="px-4 py-3"><StatusChip status={row.status} /></td>
              </tr>
            ))}
            {visible.length === 0 && (
              <tr><td colSpan="6" className="px-4 py-10 text-center text-dim">
                {query
                  ? <>No match for “{query}”.</>
                  : <>No candidates yet — run <code className="text-marigold">./tools/seed.sh 5</code> and watch them appear live.</>}
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
