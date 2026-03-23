import axios from 'axios'

// 创建 axios 实例
const http = axios.create({
  // 开发环境通过 vite.config.js 的 proxy 转发，生产环境替换为真实域名
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

// 响应拦截器
http.interceptors.response.use(
  response => response.data,
  error => {
    console.error('[HTTP错误]', error.message)
    return Promise.reject(error)
  }
)

/**
 * 上传单条玩家发言数据
 * @param {Object} payload - 发言数据
 * @param {string} payload.playerId  - 玩家ID
 * @param {string} payload.content   - 发言内容
 * @param {string} payload.platform  - 来源平台
 * @returns {Promise}
 */
export function uploadChatMessage(payload) {
  return http.post('/chat/upload', payload)
}
