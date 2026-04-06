<template>
  <div class="board-wrap">
    <header class="board-header">
      <h1>全服舆情实时看板</h1>
      <el-button @click="refresh">刷新</el-button>
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

const metrics = reactive({
  total: 0,
  blocked: 0,
  review: 0,
  blockRate: 0,
  latest: []
})

const trendChartRef = ref(null)
let chart = null
let ws = null
const points = reactive({
  x: [],
  blockRate: [],
  review: []
})

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

function applyPayload(payload) {
  if (!payload || payload.code !== 200 || !payload.data) return
  Object.assign(metrics, payload.data)
  pushPoint()
}

async function refresh() {
  const res = await fetchDashboardMetrics()
  applyPayload(res)
}

function initWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${protocol}://${window.location.host}/ws/dashboard`)

  ws.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data)
      applyPayload(payload)
    } catch (_) {
      // ignore
    }
  }

  ws.onclose = () => {
    setTimeout(initWebSocket, 3000)
  }
}

onMounted(async () => {
  await nextTick()
  initChart()
  await refresh()
  initWebSocket()
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  if (ws) ws.close()
  if (chart) chart.dispose()
  window.removeEventListener('resize', resizeChart)
})

function resizeChart() {
  if (chart) chart.resize()
}
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
