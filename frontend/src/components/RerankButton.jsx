import { useEffect, useState } from 'react'
import { Sparkles, Loader2 } from 'lucide-react'
import { api } from '../api'

/**
 * "Rerank top 50 with AI": starts the background rerank, then polls until it finishes.
 * New scores arrive on the page by themselves (SSE) while it runs; onDone reloads once at the end.
 * Free-tier note: one full rerank uses ~35-40K of the stronger model's 200K tokens/day.
 */
export default function RerankButton({ jobId, onDone }) {
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState(null)

  useEffect(() => { // a rerank may already be running (another tab, or a page reload)
    api.rerankStatus(jobId).then((s) => setRunning(s.running)).catch(() => {})
  }, [jobId])

  useEffect(() => {
    if (!running) return undefined
    const id = setInterval(async () => {
      try {
        const s = await api.rerankStatus(jobId)
        if (!s.running) {
          setRunning(false)
          setMessage('AI rerank finished — scores updated')
          onDone?.()
        }
      } catch { /* keep polling */ }
    }, 5000)
    return () => clearInterval(id)
  }, [running, jobId, onDone])

  const start = async () => {
    setMessage(null)
    try {
      const r = await api.rerank(jobId)
      setRunning(true)
      setMessage(`reranking ${r.candidates} candidates in ${r.batches} AI calls — about ${Math.ceil(r.batches * 0.6)} min`)
    } catch (e) {
      setMessage(e.message.includes('409') ? 'a rerank is already running (or nothing to rerank yet)'
        : e.message.includes('503') ? 'no AI configured for the api (LLM_PROVIDER)' : e.message)
    }
  }

  return (
    <div className="flex items-center gap-2">
      {message && <span className="max-w-xs truncate text-xs text-dim" title={message}>{message}</span>}
      <button onClick={start} disabled={running}
              title="The AI reads the top 50 like a senior recruiter and nudges each score by at most ±10%, with written reasons"
              className="flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-violet to-cyan px-3 py-1.5 text-sm font-medium text-white shadow-lg shadow-violet/30 disabled:opacity-60">
        {running ? <Loader2 size={14} className="animate-spin" /> : <Sparkles size={14} />}
        {running ? 'AI reranking…' : 'Rerank top 50 with AI'}
      </button>
    </div>
  )
}
