import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { LogIn, UserPlus } from 'lucide-react'
import { login, signup } from '../auth'

const input = 'w-full rounded-lg bg-white/5 px-3 py-2 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50'

/** Candidate sign-up and sign-in — one form, two modes. Server messages are shown as they come. */
export default function AuthForm({ mode }) {
  const isSignup = mode === 'signup'
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await (isSignup ? signup(email, password) : login(email, password))
      navigate('/candidate', { replace: true })
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="card card-strong mx-auto w-full max-w-sm space-y-5 p-6 !rounded-2xl">
      <div>
        <h1 className="text-lg font-semibold">{isSignup ? 'Create your candidate account' : 'Sign in'}</h1>
        <p className="mt-1 text-xs text-dim">
          {isSignup
            ? 'Track your applications here. Use the e-mail you applied with — after you verify it, those applications appear on your dashboard.'
            : 'Welcome back — sign in to see your applications.'}
        </p>
      </div>

      <label className="block">
        <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Email</div>
        <input type="email" autoComplete="username" required value={email}
               onChange={(e) => setEmail(e.target.value)} className={input} />
      </label>
      <label className="block">
        <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Password</div>
        <input type="password" autoComplete={isSignup ? 'new-password' : 'current-password'} required
               minLength={isSignup ? 10 : undefined} value={password}
               onChange={(e) => setPassword(e.target.value)} className={input} />
        {isSignup && <div className="mt-1 text-[11px] text-dim/70">at least 10 characters</div>}
      </label>

      {error && <p className="text-sm text-rose-400">{error}</p>}

      <button disabled={busy || !email || !password}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30 disabled:opacity-40">
        {isSignup ? <UserPlus size={15} /> : <LogIn size={15} />}
        {busy ? (isSignup ? 'Creating…' : 'Signing in…') : (isSignup ? 'Create account' : 'Sign in')}
      </button>

      <div className="flex flex-wrap justify-between gap-2 text-xs text-dim">
        {isSignup
          ? <Link to="/candidate/login" className="text-cyan hover:underline">Have an account? Sign in</Link>
          : <Link to="/candidate/signup" className="text-cyan hover:underline">New here? Create an account</Link>}
        <Link to="/" className="hover:text-ink">Recruiter? Open the console →</Link>
      </div>
    </form>
  )
}
