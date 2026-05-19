import http from './http'

/**
 * 调用归档检索接口。
 * 后端契约：GET /api/archive/search 支持 decision / keyword / playerId / labels / page / limit。
 */
export function searchArchive(params = {}) {
  return http.get('/archive/search', { params })
}

/**
 * 调用归档检索高亮接口。
 */
export function searchArchiveWithHighlight(params = {}) {
  return http.get('/archive/search/highlight', { params })
}

/**
 * 调用归档聚合分析接口。
 */
export function analyzeArchive(params = {}) {
  return http.get('/archive/analysis', { params })
}

/**
 * 仅拉取 BLOCK 决策的发言，用于封禁分析大屏。
 */
export function searchBlockedMessages(limit = 500, page = 1) {
  return searchArchive({ decision: 'BLOCK', page, limit })
}

/**
 * 仅拉取 BLOCK 决策的高亮检索结果。
 */
export function searchBlockedMessagesWithHighlight(keyword, limit = 100, page = 1) {
  return searchArchiveWithHighlight({ keyword, decision: 'BLOCK', page, limit })
}

/**
 * 拉取 BLOCK 决策的归档聚合分析。
 */
export function analyzeBlockedMessages(topHitLimit = 5) {
  return analyzeArchive({ decision: 'BLOCK', topHitLimit })
}
