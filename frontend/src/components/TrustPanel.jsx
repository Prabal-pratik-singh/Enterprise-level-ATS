import { useState } from 'react'
import { ShieldAlert, ShieldCheck } from 'lucide-react'
import { api } from '../api'
import { FLAG_THRESHOLD, flagMeta, pts } from '../lib/fraud'

/** The Trust Service's findings: every flag in plain English + how it moved the score. */
export function TrustFindings({ flags, fraudScore, matchScore, fraudPenalty, finalScore }) {
  if (!flags || flags.length === 0) return null
  const flagged = (fraudScore ?? 0) >= FLAG_THRESHOLD
  return (
    <div className="space-y-3">
      <ul className="space-y-2">
        {[...flags].sort((x, y) => y.weight - x.weight).map((flag, i) => { // strongest signal first
          const { label, icon: Icon } = flagMeta(flag.code)
          return (
            <li key={i} className="flex gap-2.5 rounded-xl bg-amber-400/5 p-3 ring-1 ring-amber-400/20">
              <Icon size={15} className="mt-0.5 shrink-0 text-amber-300" />
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-xs font-medium text-amber-300">
                  {label}
                  <span className="rounded-full bg-white/5 px-1.5 text-[10px] tabular-nums text-dim ring-1 ring-white/10">
                    weight {flag.weight}
                  </span>
                </div>
                <p className="mt-0.5 break-words text-xs leading-snug text-ink/80">{flag.explanation}</p>
              </div>
            </li>
          )
        })}
      </ul>

      {/* the score maths, spelled out — nothing hidden from the recruiter */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-dim">
        <span>fraud score <b className="tabular-nums text-amber-300">{fraudScore?.toFixed(2)}</b></span>
        <span className="text-dim/40">·</span>
        {flagged ? (
          <span className="tabular-nums">
            match <b className="text-ink">{pts(matchScore)}</b> − penalty{' '}
            <b className={fraudPenalty > 0 ? 'text-amber-300' : 'text-emerald-300'}>{pts(fraudPenalty)}</b> = final{' '}
            <b className="text-marigold">{pts(finalScore)}</b>
          </span>
        ) : (
          <span>below the {FLAG_THRESHOLD} threshold — shown for context, costs no points</span>
        )}
      </div>
    </div>
  )
}

/**
 * The human verdict on a flag. CLEAR = false alarm (points come back);
 * CONFIRM = fraud (rejected). The reason lands in the audit trail.
 */
export function ReviewActions({ applicationId, matchScore, onReviewed, compact = false }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const submit = async (verdict) => {
    setBusy(true)
    setError(null)
    try {
      const { status } = await api.fraudReview(applicationId, verdict, reason)
      // CLEAR gives the points back: final = match again
      onReviewed(applicationId, status, verdict === 'CLEAR'
        ? { fraudPenalty: 0, finalScore: matchScore ?? null }
        : {})
    } catch (e) {
      setError(e.message)
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      <input value={reason} onChange={(e) => setReason(e.target.value)} disabled={busy}
             placeholder="Reason (optional) — saved to the audit trail"
             className={`w-full rounded-xl bg-white/5 px-3 text-sm ring-1 ring-white/10 placeholder:text-dim/50 focus:outline-none focus:ring-violet/50 ${compact ? 'py-1.5' : 'py-2'}`} />
      <div className="flex gap-2">
        <button onClick={() => submit('CLEAR')} disabled={busy}
                className="flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2 text-sm font-medium text-emerald-300 ring-1 ring-emerald-400/40 transition hover:bg-emerald-400/10 disabled:opacity-50">
          <ShieldCheck size={15} /> Clear — false alarm
        </button>
        <button onClick={() => submit('CONFIRM')} disabled={busy}
                className="flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2 text-sm font-medium text-rose-300 ring-1 ring-rose-400/40 transition hover:bg-rose-400/10 disabled:opacity-50">
          <ShieldAlert size={15} /> Confirm fraud — reject
        </button>
      </div>
      {error && <p className="text-xs text-rose-400">{error}</p>}
    </div>
  )
}
