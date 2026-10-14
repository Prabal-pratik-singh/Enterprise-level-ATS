import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ShieldAlert, ShieldCheck } from 'lucide-react'
import { api } from '../api'
import Avatar from '../components/Avatar'
import CandidateDrawer from '../components/CandidateDrawer'
import { ReviewActions, TrustFindings } from '../components/TrustPanel'

/**
 * The human half of the Trust Service: every application the machine flagged,
 * most suspicious first, with the evidence and the two possible verdicts.
 * The system never rejects on its own — this page is where a person does.
 */
export default function ReviewQueuePage() {
  const [items, setItems] = useState(null)
  const [error, setError] = useState(null)
  const [done, setDone] = useState([])        // verdicts given on this visit: [{ name, status }]
  const [selected, setSelected] = useState(null)

  const load = () => api.reviewQueue().then(setItems).catch((e) => setError(e.message))
  useEffect(() => { load() }, [])

  // a verdict removes the card; keep a short receipt underneath
  const reviewed = (applicationId, status) => {
    const item = items.find((i) => i.applicationId === applicationId)
    setItems((current) => current.filter((i) => i.applicationId !== applicationId))
    setDone((list) => [{ name: item?.name, status }, ...list])
  }

  if (error) return <p className="text-rose-400">{error}</p>
  if (!items) return <p className="text-dim">Loading review queue…</p>

  return (
    <div>
      <div className="mb-1 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Review queue</h1>
        <span className="text-base text-dim">({items.length} waiting)</span>
      </div>
      <p className="mb-5 max-w-3xl text-sm text-dim">
        The Trust Service flagged these resumes (fraud score ≥ 0.5) and took points off their ranking.
        It never rejects anyone by itself — <span className="text-ink/80">clear</span> a false alarm to give the
        points back, or <span className="text-ink/80">confirm</span> to reject.
      </p>

      {items.length === 0 && (
        <div className="card flex flex-col items-center gap-2 p-10 text-center">
          <ShieldCheck size={32} className="text-emerald-300" />
          <div className="font-medium">Queue is clear</div>
          <p className="text-sm text-dim">Nothing is waiting on a fraud verdict.</p>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {items.map((item) => (
          <div key={item.applicationId} className="card flex flex-col gap-4 p-5">
            <div className="flex items-start justify-between gap-3">
              <button onClick={() => setSelected(item.applicationId)} className="flex items-center gap-3 text-left">
                <Avatar name={item.name} size={40} />
                <div className="leading-tight">
                  <div className="font-medium hover:underline">{item.name}</div>
                  <div className="text-xs text-dim">{item.email}</div>
                </div>
              </button>
              <div className="text-right">
                <div className="flex items-center justify-end gap-1.5 text-amber-300">
                  <ShieldAlert size={15} />
                  <span className="text-2xl font-semibold tabular-nums">{item.fraudScore?.toFixed(2)}</span>
                </div>
                <div className="text-[11px] text-dim">fraud score</div>
              </div>
            </div>

            <TrustFindings flags={item.flags} fraudScore={item.fraudScore} matchScore={item.matchScore}
                           fraudPenalty={item.fraudPenalty} finalScore={item.finalScore} />

            <div className="mt-auto">
              <ReviewActions applicationId={item.applicationId} matchScore={item.matchScore}
                             onReviewed={reviewed} compact />
            </div>
          </div>
        ))}
      </div>

      {done.length > 0 && (
        <div className="mt-6 card p-4">
          <div className="mb-2 text-sm font-medium">Decided this visit</div>
          <ul className="space-y-1 text-sm">
            {done.map((d, i) => (
              <li key={i} className="flex gap-2">
                <span className={d.status === 'REJECTED' ? 'text-rose-300' : 'text-emerald-300'}>
                  {d.status === 'REJECTED' ? '✕ confirmed fraud' : '✓ cleared'}
                </span>
                <span>{d.name}</span>
                <span className="text-dim">→ {d.status.toLowerCase()}</span>
              </li>
            ))}
          </ul>
          <Link to="/candidates" className="mt-2 inline-block text-xs text-cyan hover:underline">
            See the updated ranking →
          </Link>
        </div>
      )}

      {selected && (
        <CandidateDrawer applicationId={selected}
                         onClose={() => setSelected(null)}
                         onDecision={(id, status) => { if (status !== 'FLAGGED_FOR_REVIEW') reviewed(id, status) }} />
      )}
    </div>
  )
}
