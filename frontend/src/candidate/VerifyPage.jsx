import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { MailCheck, MailX, Loader2 } from 'lucide-react'
import { verifyEmail } from '../auth'
import { api } from '../api'

/** Where the e-mailed link lands: /candidate/verify?token=… — verifies once, then says what changed. */
export default function VerifyPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [state, setState] = useState({ phase: 'working' }) // working | done | failed
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return // React's dev mode runs effects twice; the link only needs one call
    started.current = true
    if (!token) {
      setState({ phase: 'failed', message: 'This link is missing its token — open it straight from the e-mail.' })
      return
    }
    verifyEmail(token)
      .then(async ({ user, mine }) => {
        const linked = mine ? (await api.myApplications().catch(() => null))?.length : null
        setState({ phase: 'done', email: user.email, mine, linked })
      })
      .catch((e) => setState({ phase: 'failed', message: sentence(e.message) }))
  }, [token])

  return (
    <div className="card card-strong mx-auto w-full max-w-md space-y-4 p-6 text-center !rounded-2xl">
      {state.phase === 'working' && (
        <>
          <Loader2 size={28} className="mx-auto animate-spin text-cyan" />
          <p className="text-sm text-dim">Verifying your e-mail…</p>
        </>
      )}
      {state.phase === 'done' && (
        <>
          <MailCheck size={30} className="mx-auto text-emerald-300" />
          <h1 className="text-lg font-semibold">Your e-mail is verified</h1>
          <p className="text-sm text-dim">
            {state.mine
              ? linkedText(state.linked)
              : <>Sign in as <span className="text-ink">{state.email}</span> to see the applications you sent with it.</>}
          </p>
          <Link to={state.mine ? '/candidate' : '/candidate/login'}
                className="inline-block rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30">
            {state.mine ? 'Open my dashboard' : 'Sign in'}
          </Link>
        </>
      )}
      {state.phase === 'failed' && (
        <>
          <MailX size={30} className="mx-auto text-rose-300" />
          <h1 className="text-lg font-semibold">This link didn't work</h1>
          <p className="text-sm text-dim">{state.message}</p>
          <Link to="/candidate" className="text-sm text-cyan hover:underline">Sign in and send a new link</Link>
        </>
      )}
    </div>
  )
}

function linkedText(count) {
  if (count == null) return 'Applications you sent with this e-mail now appear on your dashboard.'
  if (count === 0) return 'No earlier applications used this e-mail — new ones you send will appear on your dashboard.'
  return `${count} earlier application${count === 1 ? ' is' : 's are'} now on your dashboard.`
}

// "this link expired — ask for a new one" → "This link expired — ask for a new one."
function sentence(text = '') {
  const t = text.trim()
  return t ? t[0].toUpperCase() + t.slice(1) + (/[.!?]$/.test(t) ? '' : '.') : 'Something went wrong.'
}
