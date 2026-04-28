<template>
  <div class="board-wrap">
    <header class="board-header">
      <div class="head-block">
        <div class="live-dot">
          <span class="pulse" />
          <span class="solid" />
        </div>
        <div class="titles">
          <p class="eyebrow">全链路监控</p>
          <h1>全服舆情实时看板</h1>
          <p class="sub">Kafka / MQ · 抽样 100 条 · 双线趋势 & 分区对比</p>
        </div>
      </div>
      <div class="header-right">
        <span class="ws-indicator" :class="{ online: wsConnected }">
          {{ wsConnected ? 'WS 在线' : 'WS 重连中' }}
        </span>
        <el-button type="primary" class="dash-refresh" @click="refreshNow">刷新</el-button>
      </div>
    </header>

    <section class="kpi-grid">
      <div class="kpi">
        <span class="label">总消息量</span>
        <span class="val">{{ metrics.total || 0 }}</span>
      </div>
      <div class="kpi">
        <span class="label">已拦截</span>
        <span class="val warn">{{ metrics.blocked || 0 }}</span>
      </div>
      <div class="kpi">
        <span class="label">待复核</span>
        <span class="val">{{ metrics.review || 0 }}</span>
      </div>
      <div class="kpi">
        <span class="label">拦截率</span>
        <span class="val danger">{{ formatRate(metrics.blockRate) }}%</span>
      </div>
    </section>

    <section class="chart-panel">
      <h3>情绪/违规波动（近30次刷新）</h3>
      <div ref="trendChartRef" class="chart"></div>
    </section>

    <section class="analysis-grid">
      <div class="panel">
        <h3>热点词云（最近100条）</h3>
        <div class="word-cloud">
          <span
            v-for="(item, idx) in metrics.hotWords"
            :key="`${item.word}-${idx}`"
            class="word-item"
            :style="wordStyle(item, idx)"
          >
            {{ item.word }}
          </span>
          <span v-if="!metrics.hotWords.length" class="word-empty">暂无热点词</span>
        </div>
      </div>

      <div class="panel">
        <h3>分区对比图（平台分布）</h3>
        <div ref="platformChartRef" class="platform-chart"></div>
      </div>
    </section>

    <section class="stream-panel">
      <h3>最新消息流（最近100条）</h3>
      <div class="stream-list">
        <div class="stream-row" v-for="item in metrics.latest || []" :key="item.id">
          <span class="id">{{ item.playerId }}</span>
          <span class="txt">{{ item.content }}</span>
          <span class="tag" :class="tagClass(item.decision)">{{ item.decision }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { fetchDashboardMetrics } from '../api/dashboard'

// @author AI (under P9_Dashboard_FE supervision)
const REALTIME_INTERVAL_MS = 1000
const TREND_INTERVAL_MS = 5000
const HEARTBEAT_INTERVAL_MS = 30000
const MAX_RECONNECT_TIMES = 5

const metrics = reactive({
  total: 0,
  blocked: 0,
  review: 0,
  blockRate: 0,
  latest: [],
  hotWords: [],
  platformDistribution: {}
})

const trendChartRef = ref(null)
const platformChartRef = ref(null)
const wsConnected = ref(false)

const points = reactive({
  x: [],
  blockRate: [],
  review: []
})

let chart = null
let platformChart = null
let ws = null
let rafId = 0
let lastRealtimeAt = 0
let lastTrendAt = 0
let reconnectTimes = 0
let reconnectTimer = null
let heartbeatTimer = null
let refreshRunning = false
let isUnmounted = false

function formatRate(val) {
  return Number(val || 0).toFixed(2)
}

function tagClass(decision) {
  if (decision === 'BLOCK') return 'danger'
  if (decision === 'REVIEW') return 'warn'
  return 'ok'
}

function initChart() {
  if (!trendChartRef.value) return
  chart = echarts.init(trendChartRef.value)
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#f1f5f9' }
    },
    legend: {
      data: ['拦截率%', '待复核量'],
      textStyle: { color: '#bfd2ff' }
    },
    xAxis: {
      type: 'category',
      data: points.x,
      axisLine: { lineStyle: { color: '#3d5a80' } },
      axisLabel: { color: '#8ba3c7' }
    },
    yAxis: [
      {
        type: 'value',
        name: '拦截率%',
        nameTextStyle: { color: '#94a8c8' },
        axisLine: { lineStyle: { color: '#3d5a80' } },
        axisLabel: { color: '#8ba3c7' },
        splitLine: { lineStyle: { color: '#1a2840' } }
      },
      {
        type: 'value',
        name: '待复核',
        nameTextStyle: { color: '#94a8c8' },
        axisLine: { lineStyle: { color: '#3d5a80' } },
        axisLabel: { color: '#8ba3c7' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '拦截率%',
        type: 'line',
        smooth: true,
        data: points.blockRate,
        yAxisIndex: 0,
        lineStyle: { color: '#fb7185', width: 3, shadowBlur: 8, shadowColor: 'rgba(251,113,133,0.45)' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(251,113,133,0.25)' },
            { offset: 1, color: 'transparent' }
          ])
        }
      },
      {
        name: '待复核量',
        type: 'bar',
        data: points.review,
        yAxisIndex: 1,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#38bdf8' },
            { offset: 1, color: '#0369a1' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      }
    ]
  })
}

function initPlatformChart() {
  if (!platformChartRef.value) return
  platformChart = echarts.init(platformChartRef.value)
  renderPlatformChart()
}

function renderPlatformChart() {
  if (!platformChart) return
  const dist = metrics.platformDistribution || {}
  const entries = Object.entries(dist)
    .map(([name, value]) => ({ name, value: Number(value || 0) }))
    .sort((a, b) => b.value - a.value)

  platformChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#e2e8f0' }
    },
    grid: { left: 56, right: 18, top: 28, bottom: 28 },
    xAxis: {
      type: 'category',
      data: entries.map((x) => x.name),
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#94a3b8', rotate: 14 }
    },
    yAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: '#334155' } },
      splitLine: { lineStyle: { color: '#1e293b' } },
      axisLabel: { color: '#94a3b8' }
    },
    series: [
      {
        type: 'bar',
        data: entries.map((x) => x.value),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#22d3ee' },
            { offset: 1, color: '#2563eb' }
          ]),
          borderRadius: [8, 8, 0, 0],
          shadowBlur: 14,
          shadowColor: 'rgba(34, 211, 238, 0.35)'
        },
        barMaxWidth: 44
      }
    ]
  })
}

function pushPoint() {
  const now = new Date()
  const key = `${now.getHours()}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`

  points.x.push(key)
  points.blockRate.push(Number(formatRate(metrics.blockRate)))
  points.review.push(Number(metrics.review || 0))

  if (points.x.length > 30) {
    points.x.shift()
    points.blockRate.shift()
    points.review.shift()
  }

  if (chart) {
    chart.setOption({
      xAxis: { data: points.x },
      series: [
        { data: points.blockRate },
        { data: points.review }
      ]
    })
  }
}

function applyMetricsData(data) {
  if (!data) return
  const latest = Array.isArray(data.latest) ? data.latest : []
  const blockedWordSet = buildBlockedWordSet(latest)
  Object.assign(metrics, {
    total: data.total || 0,
    blocked: data.blocked || 0,
    review: data.review || 0,
    blockRate: data.blockRate || 0,
    latest,
    hotWords: Array.isArray(data.hotWords)
      ? filterHotWords(data.hotWords, blockedWordSet)
      : buildHotWords(latest, blockedWordSet),
    platformDistribution: data.platformDistribution || buildPlatformDistribution(latest)
  })
  renderPlatformChart()
}

function buildPlatformDistribution(latest) {
  const summary = {}
  for (const item of latest) {
    const key = String(item?.platform || 'UNKNOWN')
    summary[key] = (summary[key] || 0) + 1
  }
  return summary
}

function tokenizeWords(text) {
  return String(text || '')
    .toLowerCase()
    .split(/[^\u4e00-\u9fa5A-Za-z0-9]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}

function buildBlockedWordSet(latest) {
  const blockedWords = new Set()
  for (const item of latest) {
    const isBlocked = item?.decision === 'BLOCK' || Number(item?.status) === 2
    if (!isBlocked) continue

    const hitWords = tokenizeWords(item?.hitWords)
    const contentWords = tokenizeWords(item?.content)

    for (const word of hitWords) {
      blockedWords.add(word)
    }
    for (const word of contentWords) {
      blockedWords.add(word)
    }
  }
  return blockedWords
}

function filterHotWords(hotWords, blockedWordSet) {
  return hotWords
    .map((x) => ({
      word: String(x?.word || '').toLowerCase(),
      count: Number(x?.count || 0)
    }))
    .filter((x) => x.word && x.count > 0 && !blockedWordSet.has(x.word))
    .slice(0, 24)
}

function buildHotWords(latest, blockedWordSet) {
  const stopWords = new Set([
    '的', '了', '是', '在', '和', '就', '都', '这', '那', '你', '我', '他',
    '她', '它', '我们', '你们', '他们', '一个', '这个', '那个', '还有', '已经',
    '可以', '一下', '真的', '就是', '然后', '但是', '因为', '所以', 'please',
    'the', 'and', 'for', 'with', 'that', 'this', 'from'
  ])
  const counter = {}

  for (const item of latest) {
    const words = tokenizeWords(item?.content)
      .filter((x) => x.length >= 2 && !stopWords.has(x))

    for (const word of words) {
      counter[word] = (counter[word] || 0) + 1
    }
  }

  return Object.entries(counter)
    .map(([word, count]) => ({ word, count }))
    .filter((x) => !blockedWordSet.has(x.word))
    .sort((a, b) => b.count - a.count)
    .slice(0, 24)
}

function wordStyle(item, idx) {
  const count = Number(item?.count || 0)
  const size = Math.max(14, Math.min(38, 12 + count * 3))
  const hue = (idx * 47) % 360
  return {
    fontSize: `${size}px`,
    color: `hsl(${hue} 72% 62%)`,
    textShadow: '0 0 18px rgba(34, 211, 238, 0.12)'
  }
}

function applyPayload(payload) {
  if (!payload) return
  if (payload.code === 200 && payload.data) {
    applyMetricsData(payload.data)
    return
  }
  if (payload.type === 'REALTIME_STATS' && payload.data) {
    applyMetricsData(payload.data)
  }
}

async function refreshNow() {
  if (refreshRunning) return
  refreshRunning = true
  try {
    const res = await fetchDashboardMetrics()
    applyPayload(res)
  } catch (_) {
    // keep silent for dashboard stability
  } finally {
    refreshRunning = false
  }
}

function stopHeartbeat() {
  if (!heartbeatTimer) return
  clearInterval(heartbeatTimer)
  heartbeatTimer = null
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('ping')
    }
  }, HEARTBEAT_INTERVAL_MS)
}

function scheduleReconnect() {
  if (isUnmounted || reconnectTimes >= MAX_RECONNECT_TIMES) return
  const delay = Math.min(1000 * (2 ** reconnectTimes), 30000)
  reconnectTimes += 1

  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
  }
  reconnectTimer = setTimeout(connectWebSocket, delay)
}

function connectWebSocket() {
  if (isUnmounted) return
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${protocol}://${window.location.host}/ws/dashboard`)

  ws.onopen = () => {
    wsConnected.value = true
    reconnectTimes = 0
    startHeartbeat()
  }

  ws.onmessage = (event) => {
    if (event.data === 'pong') return
    try {
      const payload = JSON.parse(event.data)
      applyPayload(payload)
    } catch (_) {
      // ignore malformed messages
    }
  }

  ws.onclose = () => {
    wsConnected.value = false
    stopHeartbeat()
    scheduleReconnect()
  }

  ws.onerror = () => {
    wsConnected.value = false
    stopHeartbeat()
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.close()
    }
  }
}

function tick(timestamp) {
  if (isUnmounted) return

  if (!lastRealtimeAt || timestamp - lastRealtimeAt >= REALTIME_INTERVAL_MS) {
    lastRealtimeAt = timestamp
    refreshNow()
  }

  if (!lastTrendAt || timestamp - lastTrendAt >= TREND_INTERVAL_MS) {
    lastTrendAt = timestamp
    pushPoint()
  }

  rafId = window.requestAnimationFrame(tick)
}

function startLoop() {
  stopLoop()
  rafId = window.requestAnimationFrame(tick)
}

function stopLoop() {
  if (!rafId) return
  window.cancelAnimationFrame(rafId)
  rafId = 0
}

function resizeChart() {
  if (chart) chart.resize()
  if (platformChart) platformChart.resize()
}

onMounted(async () => {
  await nextTick()
  initChart()
  initPlatformChart()
  await refreshNow()
  pushPoint()
  connectWebSocket()
  startLoop()
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  isUnmounted = true
  stopLoop()

  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  stopHeartbeat()

  if (ws) {
    ws.close()
    ws = null
  }

  if (chart) {
    chart.dispose()
    chart = null
  }
  if (platformChart) {
    platformChart.dispose()
    platformChart = null
  }

  window.removeEventListener('resize', resizeChart)
})
</script>

<style scoped>
.board-wrap {
  --card: rgba(15, 23, 42, 0.65);
  --card-bd: rgba(56, 189, 248, 0.16);
  --text: #e2e8f0;
  --muted: #8ba3c7;
  min-height: 100vh;
  padding: 20px 24px 48px;
  color: var(--text);
  background:
    radial-gradient(ellipse 140% 80% at 0% -40%, rgba(34, 211, 238, 0.12), transparent),
    radial-gradient(ellipse 100% 60% at 100% 0%, rgba(99, 102, 241, 0.1), transparent),
    linear-gradient(175deg, #020617 0%, #041020 42%, #020617 100%);
}

.board-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  padding-bottom: 18px;
  margin-bottom: 8px;
  border-bottom: 1px solid rgba(56, 189, 248, 0.12);
}

.head-block {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.live-dot {
  position: relative;
  width: 14px;
  height: 14px;
  margin-top: 8px;
  flex-shrink: 0;
}

.live-dot .solid {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 30%, #fef08a, #22d3ee);
  box-shadow: 0 0 16px rgba(34, 211, 238, 0.7);
}

.live-dot .pulse {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  background: rgba(34, 211, 238, 0.25);
  animation: ripple 2s ease-out infinite;
}

@keyframes ripple {
  0% {
    transform: scale(0.85);
    opacity: 1;
  }
  100% {
    transform: scale(1.85);
    opacity: 0;
  }
}

.titles .eyebrow {
  margin: 0 0 4px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  background: linear-gradient(90deg, #22d3ee, #a78bfa);
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
}

.titles h1 {
  margin: 0;
  font-family: Manrope, Inter, sans-serif;
  font-size: 1.6rem;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #f8fafc;
  text-shadow: 0 0 40px rgba(34, 211, 238, 0.15);
}

.titles .sub {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--muted);
  max-width: 420px;
  line-height: 1.5;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ws-indicator {
  border-radius: 999px;
  border: 1px solid rgba(251, 146, 60, 0.45);
  background: rgba(180, 83, 9, 0.15);
  color: #fed7aa;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 6px 12px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.ws-indicator.online {
  border-color: rgba(74, 222, 128, 0.55);
  background: rgba(22, 101, 52, 0.3);
  color: #bbf7d0;
}

.board-wrap :deep(.dash-refresh) {
  --el-button-hover-bg-color: #0891b2;
  background: linear-gradient(135deg, #22d3ee 0%, #0891b2 100%);
  border: none;
  color: #0f172a;
  font-weight: 700;
  border-radius: 10px;
  padding: 9px 20px;
  box-shadow: 0 8px 24px rgba(34, 211, 238, 0.25);
}

.kpi-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.kpi {
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  padding: 18px 16px;
  border: 1px solid var(--card-bd);
  background:
    radial-gradient(120% 140% at 0% -20%, rgba(34, 211, 238, 0.08), transparent),
    linear-gradient(165deg, rgba(30, 41, 59, 0.8), rgba(15, 23, 42, 0.92));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.04),
    0 12px 32px rgba(0, 0, 0, 0.35);
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.kpi::before {
  content: '';
  position: absolute;
  top: 0;
  left: 14px;
  right: 14px;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, transparent, rgba(56, 189, 248, 0.9), transparent);
}

.kpi:hover {
  transform: translateY(-2px);
  border-color: rgba(103, 232, 249, 0.45);
}

.kpi:nth-child(1)::before {
  background: linear-gradient(90deg, transparent, rgba(147, 197, 253, 0.95), transparent);
}

.kpi:nth-child(2)::before {
  background: linear-gradient(90deg, transparent, rgba(251, 113, 133, 0.95), transparent);
}

.label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--muted);
}

.val {
  display: block;
  margin-top: 10px;
  font-family: Manrope, Inter, sans-serif;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}

.val.warn {
  color: #fde047;
  text-shadow: 0 0 24px rgba(250, 204, 21, 0.25);
}
.val.danger {
  color: #fb923c;
  text-shadow: 0 0 24px rgba(251, 146, 60, 0.3);
}

.chart-panel {
  margin-top: 20px;
  border-radius: 18px;
  padding: 16px 18px;
  border: 1px solid var(--card-bd);
  background: var(--card);
  backdrop-filter: blur(14px);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.3);
}

.chart-panel h3 {
  margin: 0 0 10px;
  font-family: Manrope, Inter, sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: #cbd5f5;
}

.chart {
  height: 288px;
}

.analysis-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.panel {
  border-radius: 18px;
  padding: 16px;
  border: 1px solid var(--card-bd);
  background: var(--card);
  backdrop-filter: blur(12px);
  min-height: 280px;
}

.panel h3 {
  margin: 0 0 6px;
  font-family: Manrope, Inter, sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: #e2e8f0;
}

.word-cloud {
  min-height: 220px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 12px;
  align-content: flex-start;
  padding-top: 10px;
}

.word-item {
  display: inline-flex;
  align-items: center;
  line-height: 1.1;
  font-weight: 700;
  padding: 2px 4px;
  transition: transform 0.18s ease;
}

.word-item:hover {
  transform: scale(1.05);
}

.word-empty {
  color: var(--muted);
  font-size: 13px;
}

.platform-chart {
  height: 236px;
}

.stream-panel {
  margin-top: 16px;
  border-radius: 18px;
  padding: 16px;
  border: 1px solid rgba(251, 113, 133, 0.12);
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.75), rgba(2, 6, 23, 0.85));
}

.stream-panel h3 {
  margin: 0 0 10px;
  font-family: Manrope, Inter, sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: #e2e8f0;
}

.stream-list {
  max-height: 420px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(56, 189, 248, 0.35) transparent;
}

.stream-list::-webkit-scrollbar {
  width: 6px;
}
.stream-list::-webkit-scrollbar-thumb {
  background: rgba(56, 189, 248, 0.35);
  border-radius: 3px;
}

.stream-row {
  display: grid;
  grid-template-columns: 138px minmax(0, 1fr) 104px;
  gap: 12px;
  align-items: center;
  padding: 11px 10px;
  border-radius: 10px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.08);
}

.stream-row:nth-child(even) {
  background: rgba(15, 23, 42, 0.45);
}

.stream-row:hover {
  background: rgba(34, 211, 238, 0.06);
}

.id {
  color: #cbd5f5;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.txt {
  font-size: 13px;
  color: #b8cbdb;
  line-height: 1.45;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.tag {
  text-align: center;
  border-radius: 999px;
  font-weight: 700;
  font-size: 11px;
  padding: 4px 8px;
  letter-spacing: 0.03em;
}

.tag.ok {
  background: rgba(22, 101, 52, 0.45);
  color: #86efac;
  border: 1px solid rgba(74, 222, 128, 0.35);
}
.tag.warn {
  background: rgba(161, 98, 7, 0.4);
  color: #fef08a;
  border: 1px solid rgba(250, 204, 21, 0.35);
}
.tag.danger {
  background: rgba(127, 29, 29, 0.45);
  color: #fecaca;
  border: 1px solid rgba(252, 165, 165, 0.35);
}

@media (max-width: 1100px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .analysis-grid {
    grid-template-columns: 1fr;
  }
}
</style>

