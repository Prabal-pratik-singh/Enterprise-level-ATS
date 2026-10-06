import { useEffect, useState } from 'react'
import { TriangleAlert } from 'lucide-react'
import { api } from '../api'
import { explainFailure, rootCause } from '../lib/failures'
import Avatar from './Avatar'
import ScoreBars from './ScoreBars'
import StatusChip from './StatusChip'

/** Extra line under a timeline entry — the audit trail's details, made readable. */
function timelineDetail(entry) {
  const d = entry.details || {}
  if (d.error) return { text: rootCause(d.error), tone: 'error' }
  if (entry.event === 'PARSED' && d.parse_method)
    return { text: `${d.parse_method}${d.page_count ? ` · ${d.page_count} page(s)` : ''}`, tone: 'dim' }
  if (d.reason) return { text: `"${d.reason}"`, tone: 'dim' } // the recruiter's decision note
  return null
}

/** Right-side glass panel: the full explainable report for one candidate. */
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
  // why did parsing fail? the audit trail already carries the error text
  const failureRaw = summary?.status === 'PARSE_FAILED'
    ? (report?.timeline || []).find((e) => e.event === 'PARSE_FAILED')?.details?.error
    : null

  return (
    <div className="fixed inset-0 z-30" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" />
      <aside onClick={(e) => e.stopPropagation()}
             className="absolute right-0 top-0 h-full w-full max-w-xl overflow-y-auto border-l border-white/10 bg-panel/90 p-6 shadow-2xl backdrop-blur-2xl">
        {error && <p className="text-rose-400">{error}</p>}
        {!report && !error && <p className="text-dim">Loading report…</p>}
        {report && (
          <div className="space-y-6">
            <div className="flex items-start justify-between gap-4">
              <div className="flex items-center gap-3">
                <Avatar name={summary?.name || '?'} size={48} />
                <div>
                  <h2 className="text-xl font-semibold">{summary?.name}</h2>
                  <p className="text-sm text-dim">{summary?.email}</p>
                  <div className="mt-1"><StatusChip status={summary?.status} /></div>
                </div>
              </div>
              <div className="text-right">
                <div className="bg-gradient-to-r from-marigold to-pink bg-clip-text text-4xl font-bold tabular-nums text-transparent">
                  {summary?.finalScore != null ? Math.round(summary.finalScore) : '—'}
                </div>
                <div className="text-xs text-dim">final score</div>
              </div>
            </div>

            {summary?.status === 'PARSE_FAILED' && (
              <section className="rounded-xl bg-rose-400/10 p-4 ring-1 ring-rose-400/30">
                <h3 className="flex items-center gap-2 font-semibold text-rose-300">
                  <TriangleAlert size={15} /> Why this application failed
                </h3>
                <p className="mt-1.5 text-sm leading-snug text-rose-200/90">{explainFailure(failureRaw)}</p>
                {failureRaw && (
                  <p className="mt-1.5 break-words font-mono text-[11px] leading-snug text-dim/60">
                    {rootCause(failureRaw)}
                  </p>
                )}
                <p className="mt-2 text-xs leading-snug text-dim">
                  The parser retried 3 times, then parked the file in the dead-letter queue — no text, so no
                  profile and no score. Ask the candidate to re-upload a clean PDF or DOCX.
                </p>
              </section>
            )}

            <div className="flex gap-2">
              <button onClick={() => decide('SHORTLISTED')} disabled={busy}
                      className="flex-1 rounded-xl bg-gradient-to-r from-violet to-cyan py-2 font-medium text-white shadow-lg shadow-violet/30 transition hover:shadow-violet/50 disabled:opacity-50">
                Shortlist
              </button>
              <button onClick={() => decide('REJECTED')} disabled={busy}
                      className="flex-1 rounded-xl py-2 font-medium text-rose-300 ring-1 ring-rose-400/40 transition hover:bg-rose-400/10 disabled:opacity-50">
                Reject
              </button>
            </div>

            {report.components && (
              <section>
                <h3 className="mb-2 font-semibold">Score breakdown</h3>
                <ScoreBars components={report.components} />
              </section>
            )}

            {(report.evidence || []).length > 0 && (
            <section>
              <h3 className="mb-2 font-semibold">Evidence</h3>
              <div className="flex flex-wrap gap-1.5">
                {(report.evidence || []).map((item, i) => (
                  <span key={i}
                        className={`rounded-full px-2.5 py-1 text-xs ring-1 ${
                          item.positive
                            ? 'bg-emerald-400/10 text-emerald-300 ring-emerald-400/30'
                            : 'bg-rose-400/10 text-rose-300 ring-rose-400/30'}`}>
                    {item.text}
                  </span>
                ))}
              </div>
            </section>
            )}

            {profile?.summary && (
              <section>
                <h3 className="mb-1 font-semibold">Summary</h3>
                <p className="text-sm text-ink/80">{profile.summary}</p>
              </section>
            )}

            {profile && (<>
            <section>
              <h3 className="mb-2 font-semibold">Experience</h3>
              <div className="space-y-3">
                {(profile?.experience || []).map((role, i) => (
                  <div key={i} className="rounded-xl bg-white/5 p-3 ring-1 ring-white/10">
                    <div className="text-sm font-medium">{role.title} — {role.company}</div>
                    <div className="text-xs text-dim">
                      {role.start_date} → {role.current ? 'present' : role.end_date}
                    </div>
                    {role.description && <p className="mt-1 text-xs text-ink/70">{role.description}</p>}
                  </div>
                ))}
              </div>
            </section>

            <div className="grid grid-cols-2 gap-4">
              <section>
                <h3 className="mb-2 font-semibold">Education</h3>
                {(profile?.education || []).map((edu, i) => (
                  <p key={i} className="text-sm text-ink/80">
                    {edu.degree}{edu.institution ? `, ${edu.institution}` : ''}{edu.year ? ` (${edu.year})` : ''}
                  </p>
                ))}
              </section>
              <section>
                <h3 className="mb-2 font-semibold">Projects</h3>
                {(profile?.projects || []).map((p, i) => (
                  <p key={i} className="text-sm text-ink/80">{p.name}</p>
                ))}
              </section>
            </div>

            <section>
              <h3 className="mb-2 font-semibold">Skills</h3>
              <div className="flex flex-wrap gap-1.5">
                {(profile?.skills || []).map((skill) => (
                  <span key={skill} className="rounded-lg bg-white/10 px-2 py-0.5 text-xs ring-1 ring-white/10">{skill}</span>
                ))}
              </div>
            </section>
            </>)}

            <section>
              <h3 className="mb-2 font-semibold">Timeline</h3>
              <ol className="space-y-1.5 text-xs text-ink/70">
                {(report.timeline || []).map((entry, i) => {
                  const detail = timelineDetail(entry)
                  return (
                    <li key={i} className="flex gap-2">
                      <span className="w-36 shrink-0 font-medium">{entry.event}</span>
                      <span className="min-w-0">
                        <span className="text-dim">{new Date(entry.at).toLocaleString()}</span>
                        {detail && (
                          <span className={`block break-words ${detail.tone === 'error' ? 'text-rose-300/90' : 'text-dim/60'}`}>
                            {detail.text}
                          </span>
                        )}
                      </span>
                    </li>
                  )
                })}
              </ol>
            </section>
          </div>
        )}
      </aside>
    </div>
  )
}
