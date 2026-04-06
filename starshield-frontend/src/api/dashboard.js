import http from './http'

export function fetchDashboardMetrics() {
  return http.get('/dashboard/metrics')
}
