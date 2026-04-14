<template>
  <div class="board-wrap">
    <header class="board-header">
      <h1>全服舆情实时看板</h1>
      <div class="header-right">
        <span class="ws-indicator" :class="{ online: wsConnected }">
          {{ wsConnected ? 'WS 在线' : 'WS 重连中' }}
        </span>
        <el-button @click="refreshNow">刷新</el-button>
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
    tooltip: { trigger: 'axis' },
    legend: {
      data: ['拦截率%', '待复核量'],
      textStyle: { color: '#bfd2ff' }
    },
    xAxis: {
      type: 'category',
      data: points.x,
      axisLine: { lineStyle: { color: '#48608f' } }
    },
    yAxis: [
      {
        type: 'value',
        name: '拦截率%',
        axisLine: { lineStyle: { color: '#48608f' } },
        splitLine: { lineStyle: { color: '#1d2a46' } }
      },
      {
        type: 'value',
        name: '待复核',
        axisLine: { lineStyle: { color: '#48608f' } },
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
        lineStyle: { color: '#ff6d6d', width: 2 }
      },
      {
        name: '待复核量',
        type: 'bar',
        data: points.review,
        yAxisIndex: 1,
        itemStyle: { color: '#5da8ff' }
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
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 60, right: 20, top: 26, bottom: 26 },
    xAxis: {
      type: 'category',
      data: entries.map((x) => x.name),
      axisLine: { lineStyle: { color: '#48608f' } },
      axisLabel: { color: '#bfd2ff', rotate: 12 }
    },
    yAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: '#48608f' } },
      splitLine: { lineStyle: { color: '#1d2a46' } },
      axisLabel: { color: '#bfd2ff' }
    },
    series: [
      {
        type: 'bar',
        data: entries.map((x) => x.value),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#73c1ff' },
            { offset: 1, color: '#2b6cf5' }
          ]),
          borderRadius: [6, 6, 0, 0]
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
  const size = Math.max(14, Math.min(36, 12 + count * 3))
  const hue = (idx * 41) % 360
  return {
    fontSize: `${size}px`,
    color: `hsl(${hue} 78% 72%)`
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
  min-height: 100vh;
  background: radial-gradient(circle at top right, #1a223b 0%, #080c16 55%);
  color: #dbe7ff;
  padding: 16px;
}

.board-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ws-indicator {
  border: 1px solid #6f4f2a;
  border-radius: 999px;
  background: rgba(130, 89, 36, 0.25);
  color: #ffbf73;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
}

.ws-indicator.online {
  border-color: #296b3a;
  background: rgba(45, 132, 74, 0.22);
  color: #94f2b0;
}

.kpi-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.kpi {
  border: 1px solid #2b395c;
  border-radius: 10px;
  padding: 12px;
  background: #0d1424;
}

.label { color: #9db0d8; }

.val {
  display: block;
  margin-top: 6px;
  font-size: 26px;
  font-weight: 700;
}

.val.warn { color: #ffbb55; }
.val.danger { color: #ff6d6d; }

.chart-panel {
  margin-top: 16px;
  border: 1px solid #2b395c;
  border-radius: 10px;
  padding: 12px;
  background: #0d1424;
}

.chart {
  height: 280px;
}

.analysis-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.panel {
  border: 1px solid #2b395c;
  border-radius: 10px;
  padding: 12px;
  background: #0d1424;
}

.word-cloud {
  min-height: 220px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 10px;
  align-content: flex-start;
  padding-top: 8px;
}

.word-item {
  display: inline-flex;
  align-items: center;
  line-height: 1.1;
  font-weight: 700;
  padding: 2px 4px;
}

.word-empty {
  color: #8ea3cf;
  font-size: 13px;
}

.platform-chart {
  height: 230px;
}

.stream-panel {
  margin-top: 16px;
  border: 1px solid #2b395c;
  border-radius: 10px;
  padding: 12px;
  background: #0d1424;
}

.stream-list {
  max-height: 420px;
  overflow-y: auto;
}

.stream-row {
  display: grid;
  grid-template-columns: 140px 1fr 100px;
  gap: 10px;
  border-bottom: 1px solid #17213b;
  padding: 8px 0;
}

.tag {
  text-align: center;
  border-radius: 99px;
  font-weight: 600;
  padding: 2px 8px;
}

.tag.ok { background: #173d29; color: #7cf8a6; }
.tag.warn { background: #4a3a17; color: #ffd070; }
.tag.danger { background: #4a1c1c; color: #ff9f9f; }
</style>
