// The sign-in session. The 15-minute access token lives only in memory (never localStorage, where any
// script on the page could read it). The 7-day refresh token is an httpOnly cookie the browser sends
// to /api/auth/* only — a reload or a new tab gets a fresh access token from it.
import { useEffect, useState } from 'react'

let accessToken = null
let session = { status: 'checking', user: null } // checking | signed-out | signed-in
let refreshing = null
const listeners = new Set()

function setSession(data) {
  accessToken = data?.accessToken ?? null
  session = data ? { status: 'signed-in', user: data.user } : { status: 'signed-out', user: null }
  listeners.forEach((fn) => fn(session))
}

/** The current session; re-renders the component when it changes. */
export function useSession() {
  const [state, setState] = useState(session)
  useEffect(() => {
    listeners.add(setState)
    setState(session) // it may have changed between the first render and this effect
    return () => listeners.delete(setState)
  }, [])
  return state
}

// POST to /api/auth/* and surface the server's own message ("wrong email or password",
// "too many attempts — try again in 48 s") instead of a bare HTTP status
async function postAuth(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.message || `request failed (HTTP ${res.status})`)
  return data
}

export async function login(email, password) {
  setSession(await postAuth('/api/auth/login', { email, password }))
}

/** A new candidate account — signed in at once; the verify link goes to the inbox (Mailpit here). */
export async function signup(email, password) {
  setSession(await postAuth('/api/auth/signup', { email, password }))
}

/**
 * The link from the e-mail; works signed in or out. When the person who opened it is the one signed
 * in, the session is refreshed so it shows "verified". mine = the signed-in user is that account.
 */
export async function verifyEmail(token) {
  const user = await postAuth('/api/auth/verify-email', { token })
  const mine = session.status === 'signed-in' && session.user.id === user.id
  if (mine) await refreshSession()
  return { user, mine }
}

/** A fresh verify link for the signed-in user (rate-limited by the API). */
export async function resendVerification() {
  const res = await authFetch('/api/auth/resend-verification', { method: 'POST' })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.message || `request failed (HTTP ${res.status})`)
  }
}

export async function logout() {
  await fetch('/api/auth/logout', { method: 'POST' }).catch(() => {})
  setSession(null)
}

// One refresh at a time, in this tab (the shared promise) AND across tabs (Web Locks): every refresh
// cancels the cookie's ticket, so two tabs refreshing with the same cookie at once would look like a
// stolen ticket and sign the account out everywhere.
export function refreshSession() {
  if (!refreshing) {
    const run = async () => {
      try {
        const res = await fetch('/api/auth/refresh', { method: 'POST' })
        setSession(res.ok ? await res.json() : null)
      } catch {
        if (session.status === 'checking') setSession(null) // API unreachable on first load → show sign-in
      }
      return session.status === 'signed-in'
    }
    refreshing = (navigator.locks ? navigator.locks.request('ats-refresh', run) : run())
      .finally(() => { refreshing = null })
  }
  return refreshing
}

/** "asha.k@example.com" → "Asha K" — a readable name for the avatar until profiles have one. */
export function displayName(email = '') {
  return email.split('@')[0].split(/[._-]+/).filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1)).join(' ')
}

/** fetch with the access token; a 401 (token expired) refreshes once and retries. */
export async function authFetch(path, options = {}) {
  const send = () => fetch(path, {
    ...options,
    headers: { ...options.headers, ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  })
  const res = await send()
  if (res.status === 401 && await refreshSession()) return send()
  return res
}
