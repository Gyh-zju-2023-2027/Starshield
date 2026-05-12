import http from './http'

/**
 * 调用归档检索接口。
 * 后端契约：GET /api/archive/search 支持 decision / keyword / playerId / labels / page / limit。
 */
export function searchArchive(params = {}) {
  return http.get('/archive/search', { params })
}

/**
 * 仅拉取 BLOCK 决策的发言，用于封禁分析大屏。
 */
export function searchBlockedMessages(limit = 500, page = 1) {
  return searchArchive({ decision: 'BLOCK', page, limit })
}
