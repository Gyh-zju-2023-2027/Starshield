<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command · Realtime"
      title="实时大屏"
      description="查看实时指标、平台分布、热点词和最新消息流，快速判断当前风险态势。"
    >
      <template #meta>
        <div class="grid min-w-[300px] grid-cols-2 gap-3">
          <MetricCard label="连接状态" :value="wsConnected ? '在线' : '重连中'" helper="Dashboard WS" icon="wifi" :accent="wsConnected ? 'cyan' : 'amber'" />
          <MetricCard label="最新总量" :value="metrics.total || 0" helper="当前采样汇总" icon="query_stats" accent="cyan" />
        </div>
      </template>
    </PageIntro>

    <SurfacePanel>
      <div class="ss-toolbar">
        <div class="flex flex-wrap items-center gap-2">
          <span class="ss-chip" :class="wsConnected ? 'ss-chip--accent' : ''">{{ wsConnected ? 'WebSocket 在线' : 'WebSocket 重连中' }}</span>
          <span class="ss-chip">1s 实时刷新</span>
          <span class="ss-chip">5s 趋势采样</span>
        </div>
        <el-button type="primary" class="ss-button-primary !rounded-2xl !border-none !px-5 !py-5" @click="refreshNow">刷新</el-button>
      </div>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="总消息量" :value="metrics.total || 0" helper="当前总量" icon="forum" />
        <MetricCard label="已拦截" :value="metrics.blocked || 0" helper="BLOCK 总数" icon="gpp_bad" accent="rose" />
        <MetricCard label="待复核" :value="metrics.review || 0" helper="REVIEW 总数" icon="playlist_add_check" accent="amber" />
        <MetricCard label="拦截率" :value="`${formatRate(metrics.blockRate)}%`" helper="blocked / total" icon="monitoring" accent="cyan" />
      </div>
    </SurfacePanel>

    <div class="mt-6 grid gap-6 xl:grid-cols-[1.35fr_0.95fr]">
      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">实时风险趋势</h2>
          <p class="ss-section-copy">近 30 次刷新窗口内，观察拦截率和待复核量的联动变化。</p>
        </div>
        <div ref="trendChartRef" class="h-[320px]"></div>
      </SurfacePanel>

      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">平台分布</h2>
          <p class="ss-section-copy">按平台聚合当前样本，用来判断风险是否集中在某个渠道。</p>
        </div>
        <div ref="platformChartRef" class="h-[320px]"></div>
      </SurfacePanel>
    </div>

    <div class="mt-6 grid gap-6 xl:grid-cols-[0.95fr_1.35fr]">
      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">热点词云</h2>
          <p class="ss-section-copy">最近样本提取出的高频词，便于快速感知讨论焦点。</p>
        </div>
        <div class="flex min-h-[240px] flex-wrap content-start gap-3">
          <span
            v-for="(item, idx) in metrics.hotWords"
            :key="`${item.word}-${idx}`"
            class="rounded-full border border-white/10 bg-white/[0.03] px-3 py-2 font-semibold transition hover:-translate-y-0.5"
            :style="wordStyle(item, idx)"
          >
            {{ item.word }}
          </span>
          <div v-if="!metrics.hotWords.length" class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-sm text-slate-500">
            暂无热点词
          </div>
        </div>
      </SurfacePanel>

      <SurfacePanel tone="muted" class="min-w-0 overflow-hidden">
        <div class="mb-4 flex items-center justify-between gap-3">
          <div>
            <h2 class="ss-section-title">最新消息流</h2>
            <p class="ss-section-copy">保留一份近似日志流的视图，方便你把指标波动和具体文本对上。</p>
          </div>
          <span class="ss-chip">{{ (metrics.latest || []).length }} 条</span>
        </div>
        <div class="ss-scrollbar max-h-[420px] overflow-x-hidden overflow-y-auto pr-3">
          <div
            v-for="item in metrics.latest || []"
            :key="item.id"
            class="grid min-w-0 grid-cols-[108px_minmax(0,1fr)_78px] items-start gap-3 rounded-2xl border-b border-white/8 px-1 py-3 last:border-0"
          >
            <div class="truncate text-xs text-slate-400">{{ item.playerId }}</div>
            <div class="min-w-0 break-words text-sm leading-6 text-slate-200">{{ item.content }}</div>
            <div class="min-w-0 text-right">
              <span
                class="inline-flex max-w-full rounded-full border px-2 py-1 text-[11px] font-semibold"
                :class="decisionTagClass(item.decision)"
              >
                {{ item.decision }}
              </span>
            </div>
          </div>
        </div>
      </SurfacePanel>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { fetchDashboardMetrics } from '../api/dashboard'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'
import { graphic, init } from '../utils/echartsCore'

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

function decisionTagClass(decision) {
  if (decision === 'BLOCK') return 'border-rose-400/20 bg-rose-400/10 text-rose-100'
  if (decision === 'REVIEW') return 'border-amber-400/20 bg-amber-400/10 text-amber-100'
  return 'border-emerald-400/20 bg-emerald-400/10 text-emerald-100'
}

function initChart() {
  if (!trendChartRef.value) return
  chart = init(trendChartRef.value)
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
    grid: { left: 36, right: 24, top: 34, bottom: 30 },
    xAxis: {
      type: 'category',
      data: points.x,
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#94a3b8' }
    },
    yAxis: [
      {
        type: 'value',
        name: '拦截率%',
        nameTextStyle: { color: '#94a8c8' },
        axisLine: { lineStyle: { color: '#334155' } },
        axisLabel: { color: '#94a3b8' },
        splitLine: { lineStyle: { color: '#1e293b' } }
      },
      {
        type: 'value',
        name: '待复核',
        nameTextStyle: { color: '#94a8c8' },
        axisLine: { lineStyle: { color: '#334155' } },
        axisLabel: { color: '#94a3b8' },
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
        lineStyle: { color: '#fb7185', width: 3 },
        areaStyle: {
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(255,107,87,0.22)' },
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
          borderRadius: [6, 6, 0, 0],
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#5EE6FF' },
            { offset: 1, color: '#3B82F6' }
          ])
        }
      }
    ]
  })
}

function initPlatformChart() {
  if (!platformChartRef.value) return
  platformChart = init(platformChartRef.value)
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
    grid: { left: 44, right: 18, top: 16, bottom: 28 },
    xAxis: {
      type: 'category',
      data: entries.map((x) => x.name),
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#94a3b8', rotate: 10 }
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
        barMaxWidth: 42,
        itemStyle: {
          borderRadius: [8, 8, 0, 0],
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#5EE6FF' },
            { offset: 1, color: '#3B82F6' }
          ])
        }
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
      series: [{ data: points.blockRate }, { data: points.review }]
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
    hotWords: Array.isArray(data.hotWords) ? filterHotWords(data.hotWords, blockedWordSet) : buildHotWords(latest, blockedWordSet),
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
    for (const word of hitWords) blockedWords.add(word)
    for (const word of contentWords) blockedWords.add(word)
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
    '的', '了', '是', '在', '和', '就', '都', '这', '那', '你', '我', '们',
    '她', '他', '我们', '你们', '他们', '一个', '这个', '那个', '还有', '已经',
    '可以', '真的', '就是', '然后', '但是', '因为', '所以', 'please',
    'the', 'and', 'for', 'with', 'that', 'this', 'from'
  ])
  const counter = {}

  for (const item of latest) {
    const words = tokenizeWords(item?.content).filter((x) => x.length >= 2 && !stopWords.has(x))
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
  const size = Math.max(14, Math.min(32, 12 + count * 2.2))
  const hue = (idx * 39) % 360
  return {
    fontSize: `${size}px`,
    color: `hsl(${hue} 72% 68%)`
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

  if (reconnectTimer) clearTimeout(reconnectTimer)
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
    if (ws && ws.readyState === WebSocket.OPEN) ws.close()
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
