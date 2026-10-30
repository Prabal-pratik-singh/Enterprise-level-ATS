import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { MailWarning, Briefcase, Send, FileText } from 'lucide-react'
import { api } from '../api'
import { resendVerification } from '../auth'
import CandidateStatus from './CandidateStatus'

/**
 * The candidate's home (C1: a shell). Real data only — the applications their verified e-mail
 * claimed. Profile, roles-you-fit, feedback and job matches arrive with the next Part 2 phases.
 */
export default function CandidateDashboard({ user }) {
  const [apps, setApps] = useState(null) // null = still loading
  const [error, setError] = useState(null)

  useEffect(() => {
    api.myApplications().then(setApps).catch((e) => setError(e.message))
  }, [user.emailVerified]) // verifying links the old applications — load again

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Your dashboard</h1>
        <p className="mt-1 truncate text-sm text-dim">Signed in as <span className="text-ink">{user.email}</span></p>
      </div>

      {!user.emailVerified && <VerifyBanner email={user.email} />}

      <ResumeCard />


      <section className="card p-5 !rounded-2xl">
        <div className="mb-3 flex items-center gap-2">
          <Briefcase size={16} className="text-cyan" />
          <h2 className="font-medium">My applications</h2>
          {apps && apps.length > 0 && <span className="text-sm text-dim">({apps.length})</span>}
        </div>
        {error && <p className="text-sm text-rose-400">{error}</p>}
        {!error && apps === null && <p className="text-sm text-dim">Loading…</p>}
        {!error && apps?.length === 0 && (
          <p className="text-sm text-dim">
            {user.emailVerified
              ? 'No applications yet. Applications you send with this e-mail appear here.'
              : 'Verify your e-mail first — applications you already sent with it will then appear here.'}
          </p>
        )}
        {!error && apps?.length > 0 && (
          <ul className="divide-y divide-white/10">
            {apps.map((app) => (
              <li key={app.applicationId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-3">
                <div className="min-w-0">
                  <div className="font-medium">{app.jobTitle || 'Job no longer listed'}</div>
                  <div className="text-xs text-dim">Applied {new Date(app.appliedAt).toLocaleDateString()}</div>
                </div>
                <CandidateStatus status={app.status} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** One line about the profile resume, with the way in — the page itself is /candidate/resume. */
function ResumeCard() {
  const [view, setView] = useState(undefined) // undefined = loading, null = could not load
  useEffect(() => { api.myProfile().then(setView).catch(() => setView(null)) }, [])
  if (view === undefined) return null

  const derived = view?.profile?.derived
  return (
    <Link to="/candidate/resume" className="card flex flex-wrap items-center gap-3 p-4 !rounded-2xl hover:ring-1 hover:ring-cyan/40">
      <FileText size={18} className="shrink-0 text-cyan" />
      <div className="min-w-0 flex-1 text-sm">
        <div className="font-medium">My resume</div>
        <div className="text-dim">
          {derived
            ? `${view.profile.skills?.length ?? 0} skills · ${derived.total_experience_months} months · ${view.embedded ? 'searchable' : 'being indexed'}`
            : view?.latestResume ? 'Being read…' : 'No resume yet — upload one to build your profile'}
        </div>
      </div>
      <span className="text-sm text-cyan">Open →</span>
    </Link>
  )
}

/** Until the e-mail is verified, nothing from earlier applications is linked — say why, offer a new link. */
function VerifyBanner({ email }) {
  const [state, setState] = useState(null) // null | 'sending' | 'sent' | an error message

  const resend = async () => {
    setState('sending')
    try {
      await resendVerification()
      setState('sent')
    } catch (e) {
      setState(e.message)
    }
  }

  return (
    <div className="card flex flex-wrap items-start gap-3 p-4 ring-1 ring-marigold/30 !rounded-2xl">
      <MailWarning size={18} className="mt-0.5 shrink-0 text-marigold" />
      <div className="min-w-0 flex-1 space-y-1 text-sm">
        <p className="font-medium">Verify your e-mail</p>
        <p className="text-dim">
          We sent a link to <span className="break-all text-ink">{email}</span>. Opening it proves the address is
          yours and links the applications you already sent with it. On this demo every e-mail lands
          in <a href="http://localhost:8025" target="_blank" rel="noreferrer" className="text-cyan hover:underline">Mailpit</a>.
        </p>
        {state === 'sent' && <p className="text-emerald-300">A new link is on its way.</p>}
        {state && state !== 'sending' && state !== 'sent' && <p className="text-rose-400">{state}</p>}
      </div>
      <button onClick={resend} disabled={state === 'sending'}
              className="flex items-center gap-1.5 rounded-xl bg-white/10 px-3 py-1.5 text-sm hover:bg-white/15 disabled:opacity-40">
        <Send size={14} /> {state === 'sending' ? 'Sending…' : 'Send a new link'}
      </button>
    </div>
  )
}
