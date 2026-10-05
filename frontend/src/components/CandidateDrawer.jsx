import { useEffect, useState } from 'react'
import { api } from '../api'
import ScoreBars from './ScoreBars'
import StatusChip from './StatusChip'

/** Right-side panel: the full explainable report for one candidate. */
export default function CandidateDrawer({ applicationId, onClose, onDecision }) {
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setReport(null)
    api.report(applicationId).then(setReport).catch((e) => setError(e.message))
  }, [applicationId])

  const decide = async (decision) => {
    const reason = window.prompt(`Reason for ${decision.toLowerCase()} (optional):`) ?? ''
    setBusy(true)
    try {
      await api.decide(applicationId, decision, reason)
      onDecision(applicationId, decision)
      onClose()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const summary = report?.summary
  const profile = report?.profile

  return (
    <div className="fixed inset-0 z-20" onClick={onClose}>
      <div className="absolute inset-0 bg-ink/40" />
      <aside onClick={(e) => e.stopPropagation()}
             className="absolute right-0 top-0 h-full w-full max-w-xl overflow-y-auto bg-white shadow-2xl p-6">
        {error && <p className="text-red-600">{error}</p>}
        {!report && !error && <p className="text-ink/50">Loading report…</p>}
        {report && (
          <div className="space-y-6">
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-xl font-semibold">{summary?.name}</h2>
                <p className="text-sm text-ink/60">{summary?.email}</p>
                <div className="mt-1"><StatusChip status={summary?.status} /></div>
              </div>
              <div className="text-right">
                <div className="text-3xl font-bold tabular-nums">
                  {summary?.finalScore != null ? Math.round(summary.finalScore) : '—'}
                </div>
                <div className="text-xs text-ink/50">final score</div>
              </div>
            </div>

            <div className="flex gap-2">
              <button onClick={() => decide('SHORTLISTED')} disabled={busy}
                      className="flex-1 rounded-lg bg-ink text-white py-2 font-medium hover:bg-ink-700 disabled:opacity-50">
                Shortlist
              </button>
              <button onClick={() => decide('REJECTED')} disabled={busy}
                      className="flex-1 rounded-lg border border-red-300 text-red-600 py-2 font-medium hover:bg-red-50 disabled:opacity-50">
                Reject
              </button>
            </div>

            <section>
              <h3 className="font-semibold mb-2">Score breakdown</h3>
              <ScoreBars components={report.components} />
            </section>

            <section>
              <h3 className="font-semibold mb-2">Evidence</h3>
              <div className="flex flex-wrap gap-1.5">
                {(report.evidence || []).map((item, i) => (
                  <span key={i}
                        className={`rounded-full px-2.5 py-1 text-xs ${
                          item.positive ? 'bg-green-50 text-green-700 ring-1 ring-green-200'
                                        : 'bg-red-50 text-red-700 ring-1 ring-red-200'}`}>
                    {item.text}
                  </span>
                ))}
              </div>
            </section>

            {profile?.summary && (
              <section>
                <h3 className="font-semibold mb-1">Summary</h3>
                <p className="text-sm text-ink/80">{profile.summary}</p>
              </section>
            )}

            <section>
              <h3 className="font-semibold mb-2">Experience</h3>
              <div className="space-y-3">
                {(profile?.experience || []).map((role, i) => (
                  <div key={i} className="rounded-lg bg-paper p-3">
                    <div className="font-medium text-sm">{role.title} — {role.company}</div>
                    <div className="text-xs text-ink/50">
                      {role.start_date} → {role.current ? 'present' : role.end_date}
                    </div>
                    {role.description && <p className="text-xs text-ink/70 mt-1">{role.description}</p>}
                  </div>
                ))}
              </div>
            </section>

            <div className="grid grid-cols-2 gap-4">
              <section>
                <h3 className="font-semibold mb-2">Education</h3>
                {(profile?.education || []).map((edu, i) => (
                  <p key={i} className="text-sm text-ink/80">
                    {edu.degree}{edu.institution ? `, ${edu.institution}` : ''}{edu.year ? ` (${edu.year})` : ''}
                  </p>
                ))}
              </section>
              <section>
                <h3 className="font-semibold mb-2">Projects</h3>
                {(profile?.projects || []).map((p, i) => (
                  <p key={i} className="text-sm text-ink/80">{p.name}</p>
                ))}
              </section>
            </div>

            <section>
              <h3 className="font-semibold mb-2">Skills</h3>
              <div className="flex flex-wrap gap-1.5">
                {(profile?.skills || []).map((skill) => (
                  <span key={skill} className="rounded bg-ink-100 px-2 py-0.5 text-xs">{skill}</span>
                ))}
              </div>
            </section>

            <section>
              <h3 className="font-semibold mb-2">Timeline</h3>
              <ol className="space-y-1 text-xs text-ink/70">
                {(report.timeline || []).map((entry, i) => (
                  <li key={i} className="flex gap-2">
                    <span className="font-medium w-36 shrink-0">{entry.event}</span>
                    <span className="text-ink/40">{new Date(entry.at).toLocaleString()}</span>
                  </li>
                ))}
              </ol>
            </section>
          </div>
        )}
      </aside>
    </div>
  )
}
