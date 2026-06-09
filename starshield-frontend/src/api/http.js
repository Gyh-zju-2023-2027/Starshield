import axios from 'axios'

const ADMIN_TOKEN = import.meta.env.VITE_ADMIN_API_KEY

const ADMIN_PATH_PREFIXES = ['/admin/', '/crawl']

function needsAdminToken(url, method) {
  if (!url) return false
  if (ADMIN_PATH_PREFIXES.some((p) => url.startsWith(p))) return true
  if (url.startsWith('/control/') && ['put', 'post', 'delete'].includes((method || 'get').toLowerCase())) {
    return true
  }
  if (url === '/archive/reindex' && (method || '').toLowerCase() === 'post') return true
  return false
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

http.interceptors.request.use((config) => {
  if (needsAdminToken(config.url, config.method) && ADMIN_TOKEN) {
    config.headers['X-Admin-Token'] = ADMIN_TOKEN
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(error)
)

export default http
