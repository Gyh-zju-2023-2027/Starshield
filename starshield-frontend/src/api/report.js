import http from './http'

/**
 * 获取指定日期的每日战报
 * @param {String} date 日期字符串，格式 YYYY-MM-DD
 * @returns {Promise} 返回包含报告数据的 Promise
 */
export const getDailyReport = (date) => {
  return http.get(`/reports/daily`, { params: { date } })
}
