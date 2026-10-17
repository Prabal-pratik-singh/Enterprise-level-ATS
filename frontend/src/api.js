// Tiny fetch helpers — one place for paths, JSON parsing and error handling.
async function request(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) throw new Error(`${options.method || 'GET'} ${path} → HTTP ${res.status}`)
  return res.status === 204 ? null : res.json()
}

export const api = {
  jobs: () => request('/api/jobs'),
  candidates: (jobId, page = 0, size = 100) =>
    request(`/api/jobs/${jobId}/candidates?sort=score&page=${page}&size=${size}`),
  report: (applicationId) => request(`/api/candidates/${applicationId}/report`),
  decide: (applicationId, decision, reason) =>
    request(`/api/candidates/${applicationId}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision, reason }),
    }),
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
