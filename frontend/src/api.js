// Tiny fetch helpers — one place for paths, JSON parsing and error handling.
import { authFetch } from './auth'

async function request(path, options = {}) {
  const res = await authFetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  })
  if (!res.ok) throw new Error(`${options.method || 'GET'} ${path} → HTTP ${res.status}`)
  return res.status === 204 ? null : res.json()
}

export const api = {
  jobs: () => request('/api/jobs'),
  candidates: (jobId, page = 0, size = 100) =>
    request(`/api/jobs/${jobId}/candidates?sort=score&page=${page}&size=${size}`),
  // EVERY candidate of a job: the API pages at max 200, so fetch page 0, learn the total,
  // then the rest a few pages at a time (a 10K load test = 50 pages) — lists, search and
  // dashboard numbers must cover everyone, not just the top 100/200
  allCandidates: async (jobId) => {
    const PAGE = 200
    const first = await request(`/api/jobs/${jobId}/candidates?sort=score&page=0&size=${PAGE}`)
    const pages = Math.ceil(first.total / PAGE)
    const items = [...first.items]
    for (let p = 1; p < pages; p += 4) {          // 4 requests in flight, in rank order
      const batch = await Promise.all(
        Array.from({ length: Math.min(4, pages - p) }, (_, k) =>
          request(`/api/jobs/${jobId}/candidates?sort=score&page=${p + k}&size=${PAGE}`)))
      batch.forEach((b) => items.push(...b.items))
    }
    return { total: first.total, items }
  },
  // Phase 8: LLM rerank of the top 50 — starts in the background, results stream over SSE
  rerank: (jobId) => request(`/api/jobs/${jobId}/rerank`, { method: 'POST' }),
  rerankStatus: (jobId) => request(`/api/jobs/${jobId}/rerank`),
  report: (applicationId) => request(`/api/candidates/${applicationId}/report`),
  decide: (applicationId, decision, reason) =>
    request(`/api/candidates/${applicationId}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision, reason }),
    }),
  // bulk upload: a placeholder candidate + application + presigned link, then the existing /complete
  intake: (jobId, filename) => request(`/api/jobs/${jobId}/intake`, { method: 'POST', body: JSON.stringify({ filename }) }),
  completeUpload: (applicationId, key) =>
    request(`/api/applications/${applicationId}/resume/complete`, { method: 'POST', body: JSON.stringify({ key }) }),
  skills: () => request('/api/skills'),
  createJob: (job) => request('/api/jobs', { method: 'POST', body: JSON.stringify(job) }),
  reviewQueue: (jobId) => request(`/api/review-queue${jobId ? `?jobId=${jobId}` : ''}`),
  // verdict: 'CLEAR' (false alarm — penalty lifted) | 'CONFIRM' (fraud — rejected)
  fraudReview: (applicationId, verdict, reason) =>
    request(`/api/candidates/${applicationId}/fraud-review`, {
      method: 'POST',
      body: JSON.stringify({ verdict, reason }),
    }),
  // hybrid search (Phase 7): filters are applied to BOTH the keyword and the meaning search
  search: (jobId, { q = '', skills = '', minExp = '', hideFlagged = false, size = 30 } = {}) => {
    const p = new URLSearchParams({ q, size: String(size) })
    if (skills) p.set('skills', skills)
    if (minExp) p.set('minExp', minExp)
    if (hideFlagged) p.set('hideFlagged', 'true')
    return request(`/api/jobs/${jobId}/search?${p}`)
  },
  stats: (jobId) => request(`/api/stats/pipeline${jobId ? `?jobId=${jobId}` : ''}`),
  lag: () => request('/api/admin/lag'),
}
