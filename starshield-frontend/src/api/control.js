import http from './http'

export function getSensitiveWords() {
  return http.get('/control/rules/sensitive-words')
}

export function updateSensitiveWords(words) {
  return http.put('/control/rules/sensitive-words', { words })
}

export function getPrompt() {
  return http.get('/control/prompt')
}

export function updatePrompt(prompt) {
  return http.put('/control/prompt', { prompt })
}
