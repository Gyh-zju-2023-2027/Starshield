import http from './http'

export function uploadChatMessage(payload) {
  return http.post('/chat/upload', payload)
}
