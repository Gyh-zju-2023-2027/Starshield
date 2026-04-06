import http from './http'

export function fetchPending(page = 1, pageSize = 20) {
  return http.get('/admin/moderation/pending', { params: { page, pageSize } })
}

export function fetchAuditLogs(id, limit = 20) {
  return http.get(`/admin/moderation/${id}/audit-logs`, { params: { limit } })
}

export function getIdempotencyKey() {
  return http.get('/admin/moderation/idempotency-key')
}

export async function confirmBan(id, operator = 'admin-ui') {
  const keyRes = await getIdempotencyKey()
  const idemKey = keyRes?.data?.idempotencyKey
  return http.post(`/admin/moderation/${id}/confirm-ban`, { operator }, {
    headers: {
      'X-Idempotency-Key': idemKey
    }
  })
}

export async function releaseRecord(id, operator = 'admin-ui') {
  const keyRes = await getIdempotencyKey()
  const idemKey = keyRes?.data?.idempotencyKey
  return http.post(`/admin/moderation/${id}/release`, { operator }, {
    headers: {
      'X-Idempotency-Key': idemKey
    }
  })
}
