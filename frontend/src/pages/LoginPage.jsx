import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Rocket, LogIn } from 'lucide-react'
import { login } from '../auth'

const input = 'w-full rounded-lg bg-white/5 px-3 py-2 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50'

/** Recruiter sign-in. The demo account comes from RECRUITER_EMAIL / RECRUITER_PASSWORD in .env. */
export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email, password) // on success the app swaps this page for the dashboard
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <form onSubmit={submit} className="card card-strong w-full max-w-sm space-y-5 p-6 !rounded-2xl">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-violet to-cyan shadow-lg shadow-violet/30">
            <Rocket size={18} className="text-white" />
          </div>
          <div className="leading-tight">
            <div className="font-semibold tracking-tight">ATS<span className="text-marigold">Pipeline</span></div>
            <div className="text-[10px] uppercase tracking-widest text-dim">recruiter console</div>
          </div>
        </div>

        <h1 className="text-lg font-semibold">Sign in</h1>

        <label className="block">
          <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Email</div>
          <input type="email" autoComplete="username" required value={email}
                 onChange={(e) => setEmail(e.target.value)} className={input} />
        </label>
        <label className="block">
          <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Password</div>
          <input type="password" autoComplete="current-password" required value={password}
                 onChange={(e) => setPassword(e.target.value)} className={input} />
        </label>

        {error && <p className="text-sm text-rose-400">{error}</p>}

        <button disabled={busy || !email || !password}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30 disabled:opacity-40">
          <LogIn size={15} /> {busy ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="text-[11px] leading-relaxed text-dim/70">
          Demo recruiter: the email and password are <code className="text-cyan">RECRUITER_EMAIL</code> and{' '}
          <code className="text-cyan">RECRUITER_PASSWORD</code> in your <code className="text-cyan">.env</code>.
        </p>
        <Link to="/candidate/login" className="block text-center text-xs text-cyan hover:underline">
          Looking for a job? Candidate sign-in →
        </Link>
      </form>
    </div>
  )
}
