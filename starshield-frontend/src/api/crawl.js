import http from './http'

export function submitCrawlTask(payload) {
  return http.post('/crawl/tasks', payload)
}

export function fetchCrawlTasks(limit = 20) {
  return http.get('/crawl/tasks', { params: { limit } })
}

export function stopCrawlTask(id) {
  return http.post(`/crawl/tasks/${id}/stop`)
}
