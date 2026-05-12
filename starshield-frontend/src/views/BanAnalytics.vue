<template>
  <div class="board-wrap">
    <header class="board-header">
      <div class="head-block">
        <div class="live-dot">
          <span class="pulse" />
          <span class="solid" />
        </div>
        <div class="titles">
          <p class="eyebrow">封禁全景 · BAN ANALYTICS</p>
          <h1>封禁消息分析大屏</h1>
          <p class="sub">归档检索 · decision = BLOCK · 热词 / 名单 / 排行榜</p>
        </div>
      </div>
      <div class="header-right">
        <span class="ws-indicator" :class="{ online: !loading }">
          {{ loading ? '加载中…' : `已加载 ${blocked.length} 条` }}
        </span>
        <el-button type="primary" class="dash-refresh" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </header>

    <section class="kpi-grid">
      <div class="kpi">
        <span class="label">封禁消息总数</span>
        <span class="val danger">{{ blocked.length }}</span>
      </div>
      <div class="kpi">
        <span class="label">涉及玩家数</span>
        <span class="val">{{ uniquePlayers }}</span>
      </div>
      <div class="kpi">
        <span class="label">热词命中数</span>
        <span class="val warn">{{ hotWords.length }}</span>
      </div>
      <div class="kpi">
        <span class="label">平均风险分</span>
        <span class="val danger">{{ avgRiskScore }}</span>
      </div>
    </section>

    <section class="analysis-grid">
      <div class="panel">
        <h3>封禁消息热词云（命中词 + 内容）</h3>
        <div class="word-cloud">
          <span
            v-for="(item, idx) in hotWords"
            :key="`${item.word}-${idx}`"
            class="word-item"
            :style="wordStyle(item, idx)"
            :title="`${item.word} · 出现 ${item.count} 次`"
          >
            {{ item.word }}
          </span>
          <span v-if="!hotWords.length && !loading" class="word-empty">暂无封禁热词</span>
          <span v-if="loading && !hotWords.length" class="word-empty">数据加载中…</span>
        </div>
      </div>

      <div class="panel">
        <h3>被封禁数量排行榜 · Top {{ rankTopN }}</h3>
        <div ref="rankChartRef" class="rank-chart"></div>
        <p v-if="!playerRank.length && !loading" class="word-empty">暂无封禁玩家数据</p>
      </div>
    </section>

    <section class="stream-panel">
      <div class="stream-head">
        <h3>所有封禁消息（共 {{ blocked.length }} 条）</h3>
        <div class="stream-tools">
          <el-input
            v-model="filterText"
            placeholder="按玩家 ID / 内容 / 命中词过滤"
            clearable
            size="small"
            class="filter-input"
          />
        </div>
      </div>
      <div class="stream-list">
        <div class="stream-row stream-row--head">
          <span class="col-id">玩家 ID</span>
          <span class="col-content">封禁内容</span>
          <span class="col-hit">命中词</span>
          <span class="col-risk">风险</span>
          <span class="col-time">时间</span>
        </div>
        <div
          class="stream-row"
          v-for="item in filteredList"
          :key="item.id"
        >
          <span class="col-id" :title="item.playerId">{{ item.playerId }}</span>
          <span class="col-content" :title="item.content">{{ item.content }}</span>
          <span class="col-hit">
            <span v-if="item.hitWords" class="hit-chip">{{ item.hitWords }}</span>
            <span v-else class="muted">—</span>
          </span>
          <span class="col-risk">
            <span class="tag danger">{{ item.riskScore ?? '-' }}</span>
          </span>
          <span class="col-time">{{ formatTime(item.createTime) }}</span>
        </div>
        <div v-if="!filteredList.length && !loading" class="empty-row">
          没有匹配的封禁消息
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { searchBlockedMessages } from '../api/archive'

const REFRESH_INTERVAL_MS = 15000
const FETCH_LIMIT = 500
const RANK_TOP_N = 10

const blocked = ref([])
const loading = ref(false)
const filterText = ref('')
const rankChartRef = ref(null)
const rankTopN = ref(RANK_TOP_N)

let rankChart = null
let timer = null
let unmounted = false

const STOP_WORDS = new Set([
  '的', '了', '是', '在', '和', '就', '都', '这', '那', '你', '我', '他',
  '她', '它', '我们', '你们', '他们', '一个', '这个', '那个', '还有', '已经',
  '可以', '一下', '真的', '就是', '然后', '但是', '因为', '所以', '什么',
  '怎么', '现在', '不要', 'please', 'the', 'and', 'for', 'with', 'that',
  'this', 'from', 'you', 'are', 'have', 'has', 'not', 'but', 'just',
  // B 站表情内文常见残留，二次兜底，避免方括号被去掉后单字仍混入
  'doge', 'tv', 'ovo', 'tvt', 'qaq', 'qwq', 'ojbk', '笑哭', '妙啊',
  '微笑', '滑稽', '吃瓜', '热词', '系列', '给心心', '保佑', '抓狂',
  '脱单doge', '辣眼睛', '酸了', '酸成柠檬精'
])

// B 站表情占位（如 [doge] [笑哭] [给心心] [热词系列_啊?]）和 @ 提及
const BILI_EMOJI_RE = /\[[^\[\]\n]{1,20}\]/g
const AT_MENTION_RE = /@[\w\u4e00-\u9fa5\-_]{1,32}/g

function stripBiliNoise(text) {
  return String(text || '')
    .replace(BILI_EMOJI_RE, ' ')
    .replace(AT_MENTION_RE, ' ')
}

function tokenize(text) {
  return stripBiliNoise(text)
    .toLowerCase()
    .split(/[^\u4e00-\u9fa5A-Za-z0-9]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}

const hotWords = computed(() => {
  const counter = new Map()

  for (const item of blocked.value) {
    const hitTokens = tokenize(item?.hitWords)
    for (const w of hitTokens) {
      counter.set(w, (counter.get(w) || 0) + 3)
    }

    const contentTokens = tokenize(item?.content).filter(
      (w) => w.length >= 2 && !STOP_WORDS.has(w)
    )
    for (const w of contentTokens) {
      counter.set(w, (counter.get(w) || 0) + 1)
    }
  }

  return Array.from(counter.entries())
    .map(([word, count]) => ({ word, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 40)
})

const playerRank = computed(() => {
  const counter = new Map()
  for (const item of blocked.value) {
    const pid = String(item?.playerId || '').trim()
    if (!pid) continue
    counter.set(pid, (counter.get(pid) || 0) + 1)
  }
  return Array.from(counter.entries())
    .map(([playerId, count]) => ({ playerId, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, RANK_TOP_N)
})

const uniquePlayers = computed(() => {
  const set = new Set()
  for (const item of blocked.value) {
    if (item?.playerId) set.add(item.playerId)
  }
  return set.size
})

const avgRiskScore = computed(() => {
  const scores = blocked.value
    .map((x) => Number(x?.riskScore))
    .filter((x) => Number.isFinite(x) && x > 0)
  if (!scores.length) return 0
  const sum = scores.reduce((acc, x) => acc + x, 0)
  return Math.round(sum / scores.length)
})

const filteredList = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  if (!kw) return blocked.value
  return blocked.value.filter((item) => {
    return (
      String(item?.playerId || '').toLowerCase().includes(kw) ||
      String(item?.content || '').toLowerCase().includes(kw) ||
      String(item?.hitWords || '').toLowerCase().includes(kw)
    )
  })
})

function wordStyle(item, idx) {
  const count = Number(item?.count || 0)
  const size = Math.max(13, Math.min(40, 12 + Math.log2(count + 1) * 6))
  const hue = (idx * 41 + 350) % 360
  const intensity = Math.min(72, 45 + count * 2)
  return {
    fontSize: `${size}px`,
    color: `hsl(${hue} ${intensity}% 64%)`,
    textShadow: '0 0 18px rgba(251, 113, 133, 0.18)'
  }
}

function formatTime(value) {
  if (!value) return '—'
  if (typeof value === 'string') return value
  try {
    const d = new Date(value)
    if (Number.isNaN(d.getTime())) return String(value)
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
      d.getHours()
    )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch (_) {
    return String(value)
  }
}

function initRankChart() {
  if (!rankChartRef.value) return
  rankChart = echarts.init(rankChartRef.value)
  renderRankChart()
}

function renderRankChart() {
  if (!rankChart) return
  const data = playerRank.value
  rankChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#e2e8f0' },
      formatter: (params) => {
        const p = params?.[0]
        return p ? `<b>${p.name}</b><br/>封禁条数：${p.value}` : ''
      }
    },
    grid: { left: 110, right: 24, top: 16, bottom: 24 },
    xAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: '#334155' } },
      splitLine: { lineStyle: { color: '#1e293b' } },
      axisLabel: { color: '#94a3b8' }
    },
    yAxis: {
      type: 'category',
      data: data.map((x) => x.playerId).reverse(),
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: {
        color: '#cbd5f5',
        fontFamily: 'Manrope, Inter, sans-serif',
        formatter: (val) => (val.length > 12 ? `${val.slice(0, 12)}…` : val)
      }
    },
    series: [
      {
        type: 'bar',
        data: data.map((x) => x.count).reverse(),
        barMaxWidth: 22,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: '#fb7185' },
            { offset: 1, color: '#f97316' }
          ]),
          borderRadius: [0, 8, 8, 0],
          shadowBlur: 14,
          shadowColor: 'rgba(251, 113, 133, 0.35)'
        },
        label: {
          show: true,
          position: 'right',
          color: '#fecaca',
          fontWeight: 600
        }
      }
    ]
  })
}

async function loadData() {
  if (loading.value) return
  loading.value = true
  try {
    const res = await searchBlockedMessages(FETCH_LIMIT)
    const list = Array.isArray(res?.data) ? res.data : []
    list.sort((a, b) => {
      const ta = a?.createTime ? new Date(a.createTime).getTime() : 0
      const tb = b?.createTime ? new Date(b.createTime).getTime() : 0
      return tb - ta
    })
    blocked.value = list
  } catch (_) {
    // 静默失败，保留上次数据
  } finally {
    loading.value = false
  }
}

function resizeChart() {
  if (rankChart) rankChart.resize()
}

watch(playerRank, () => {
  renderRankChart()
})

onMounted(async () => {
  await nextTick()
  initRankChart()
  await loadData()
  timer = setInterval(() => {
    if (!unmounted) loadData()
  }, REFRESH_INTERVAL_MS)
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  unmounted = true
  if (timer) clearInterval(timer)
  if (rankChart) {
    rankChart.dispose()
    rankChart = null
  }
  window.removeEventListener('resize', resizeChart)
})
</script>

<style scoped>
.board-wrap {
  --card: rgba(15, 23, 42, 0.65);
  --card-bd: rgba(251, 113, 133, 0.18);
  --text: #e2e8f0;
  --muted: #8ba3c7;
  min-height: 100vh;
  padding: 20px 24px 48px;
  color: var(--text);
  background:
    radial-gradient(ellipse 140% 80% at 0% -40%, rgba(251, 113, 133, 0.12), transparent),
    radial-gradient(ellipse 100% 60% at 100% 0%, rgba(244, 114, 182, 0.08), transparent),
    linear-gradient(175deg, #020617 0%, #0a0612 42%, #020617 100%);
}

.board-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  padding-bottom: 18px;
  margin-bottom: 8px;
  border-bottom: 1px solid rgba(251, 113, 133, 0.16);
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
  background: radial-gradient(circle at 35% 30%, #fef08a, #fb7185);
  box-shadow: 0 0 16px rgba(251, 113, 133, 0.7);
}

.live-dot .pulse {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  background: rgba(251, 113, 133, 0.25);
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
  background: linear-gradient(90deg, #fb7185, #f97316);
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
  text-shadow: 0 0 40px rgba(251, 113, 133, 0.15);
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
  --el-button-hover-bg-color: #be123c;
  background: linear-gradient(135deg, #fb7185 0%, #be123c 100%);
  border: none;
  color: #fff;
  font-weight: 700;
  border-radius: 10px;
  padding: 9px 20px;
  box-shadow: 0 8px 24px rgba(251, 113, 133, 0.28);
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
    radial-gradient(120% 140% at 0% -20%, rgba(251, 113, 133, 0.08), transparent),
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
  background: linear-gradient(90deg, transparent, rgba(251, 113, 133, 0.95), transparent);
}

.kpi:hover {
  transform: translateY(-2px);
  border-color: rgba(251, 113, 133, 0.45);
}

.kpi:nth-child(2)::before {
  background: linear-gradient(90deg, transparent, rgba(147, 197, 253, 0.95), transparent);
}

.kpi:nth-child(3)::before {
  background: linear-gradient(90deg, transparent, rgba(250, 204, 21, 0.95), transparent);
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

.analysis-grid {
  margin-top: 20px;
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
  min-height: 320px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.3);
}

.panel h3 {
  margin: 0 0 6px;
  font-family: Manrope, Inter, sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: #e2e8f0;
}

.word-cloud {
  min-height: 260px;
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
  cursor: default;
}

.word-item:hover {
  transform: scale(1.08);
}

.word-empty {
  color: var(--muted);
  font-size: 13px;
}

.rank-chart {
  height: 280px;
}

.stream-panel {
  margin-top: 16px;
  border-radius: 18px;
  padding: 16px;
  border: 1px solid rgba(251, 113, 133, 0.18);
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.78), rgba(2, 6, 23, 0.88));
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.35);
}

.stream-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.stream-head h3 {
  margin: 0;
  font-family: Manrope, Inter, sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: #e2e8f0;
}

.stream-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stream-tools :deep(.filter-input) {
  width: 260px;
}

.stream-list {
  max-height: 540px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(251, 113, 133, 0.35) transparent;
}

.stream-list::-webkit-scrollbar {
  width: 6px;
}
.stream-list::-webkit-scrollbar-thumb {
  background: rgba(251, 113, 133, 0.35);
  border-radius: 3px;
}

.stream-row {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) 180px 80px 170px;
  gap: 12px;
  align-items: center;
  padding: 10px 10px;
  border-radius: 10px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.08);
  font-size: 12.5px;
}

.stream-row--head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: rgba(2, 6, 23, 0.9);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #94a3b8;
  border-bottom: 1px solid rgba(251, 113, 133, 0.18);
}

.stream-row:not(.stream-row--head):nth-child(even) {
  background: rgba(15, 23, 42, 0.45);
}

.stream-row:not(.stream-row--head):hover {
  background: rgba(251, 113, 133, 0.07);
}

.col-id {
  color: #cbd5f5;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-content {
  color: #b8cbdb;
  line-height: 1.45;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.col-hit {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-chip {
  display: inline-block;
  max-width: 100%;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(127, 29, 29, 0.45);
  color: #fecaca;
  border: 1px solid rgba(252, 165, 165, 0.35);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted {
  color: #64748b;
}

.col-risk .tag {
  display: inline-block;
  min-width: 42px;
  text-align: center;
  border-radius: 999px;
  font-weight: 700;
  font-size: 11px;
  padding: 4px 8px;
  letter-spacing: 0.03em;
}

.tag.danger {
  background: rgba(127, 29, 29, 0.5);
  color: #fecaca;
  border: 1px solid rgba(252, 165, 165, 0.4);
}

.col-time {
  color: #94a3b8;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.empty-row {
  padding: 28px 12px;
  text-align: center;
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .analysis-grid {
    grid-template-columns: 1fr;
  }
  .stream-row {
    grid-template-columns: 100px minmax(0, 1fr) 80px;
  }
  .stream-row .col-hit,
  .stream-row .col-time {
    display: none;
  }
}
</style>
