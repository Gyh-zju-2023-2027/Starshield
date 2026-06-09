/**
 * 将用户内容转义后再渲染，防止存储型 XSS。
 */
export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * ES 高亮片段：仅允许 &lt;mark&gt; 标签，其余一律转义。
 */
export function sanitizeHighlight(value) {
  return escapeHtml(value)
    .replace(/&lt;mark&gt;/g, '<mark>')
    .replace(/&lt;\/mark&gt;/g, '</mark>')
}
