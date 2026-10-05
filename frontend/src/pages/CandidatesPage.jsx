import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams, useSearchParams, Link } from 'react-router-dom'
import { api } from '../api'
import CandidateTable from '../components/CandidateTable'
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

      <CandidateTable rows={visible} freshIds={freshIds} onSelect={setSelected}
                      emptyMessage={query
                        ? `No match for “${query}”.`
                        : 'No candidates yet — run ./tools/seed.sh 5 and watch them appear live.'} />

      {selected && (
        <CandidateDrawer applicationId={selected}
                         onClose={() => setSelected(null)}
                         onDecision={updateRowStatus} />
      )}
    </div>
  )
}
