<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command · Ban Analytics"
      title="封禁分析"
      description="按封禁样本聚合平台、热词、玩家和风险分，支持关键词高亮检索。"
    >
      <template #meta>
        <div class="grid min-w-[300px] grid-cols-2 gap-3">
          <MetricCard label="加载状态" :value="loading ? '更新中' : statusText" helper="当前分析状态" icon="sync" :accent="loading ? 'amber' : 'rose'" />
          <MetricCard label="高亮检索" :value="searchMode ? `${highlightRows.length} 条` : '未启用'" helper="关键词高亮" icon="search" accent="cyan" />
        </div>
      </template>
    </PageIntro>

    <SurfacePanel>
      <div class="ss-toolbar">
        <div class="flex flex-wrap items-center gap-2">
          <span class="ss-chip ss-chip--accent">decision = BLOCK</span>
          <span class="ss-chip">ES 聚合分析</span>
          <span class="ss-chip">高亮检索</span>
        </div>
        <div class="flex w-full flex-wrap items-center gap-3 lg:w-auto">
          <el-input
            v-model="filterText"
            placeholder="输入关键词，高亮显示封禁文本片段"
            clearable
            class="w-full lg:w-[320px]"
          />
          <el-button type="primary" class="ss-button-primary !rounded-2xl !border-none !px-5 !py-5" :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="封禁消息总数" :value="blocked.length" helper="当前样本总量" icon="block" accent="rose" />
        <MetricCard label="涉及玩家数" :value="uniquePlayers" helper="去重后玩家数量" icon="groups" />
        <MetricCard label="热词数量" :value="hotWords.length" helper="当前可见热词" icon="local_fire_department" accent="amber" />
        <MetricCard label="平均风险分" :value="avgRiskScore" helper="风险均值" icon="warning" accent="cyan" />
      </div>
    </SurfacePanel>

    <div class="mt-6 grid gap-6 xl:grid-cols-2">
      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">平台分布</h2>
          <p class="ss-section-copy">看看封禁内容主要来自哪些平台。</p>
        </div>
        <div ref="platformChartRef" class="h-[300px]"></div>
        <div v-if="!platformDistribution.length && !loading" class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-center text-sm text-slate-500">
          暂无平台聚合数据
        </div>
      </SurfacePanel>

      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">封禁趋势</h2>
          <p class="ss-section-copy">结合时间趋势判断封禁高峰是否集中在某些时段。</p>
        </div>
        <div ref="trendChartRef" class="h-[300px]"></div>
        <div v-if="!timeTrend.length && !loading" class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-center text-sm text-slate-500">
          暂无趋势聚合数据
        </div>
      </SurfacePanel>
    </div>

    <div class="mt-6 grid gap-6 xl:grid-cols-2">
      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">封禁热词</h2>
          <p class="ss-section-copy">综合命中词和内容文本抽出的热词，用来看违规内容主题。</p>
        </div>
        <div class="flex min-h-[260px] flex-wrap content-start gap-3">
          <span
            v-for="(item, idx) in hotWords"
            :key="`${item.word}-${idx}`"
            class="rounded-full border border-white/10 bg-white/[0.03] px-3 py-2 font-semibold transition hover:-translate-y-0.5"
            :style="wordStyle(item, idx)"
            :title="`${item.word} · 出现 ${item.count} 次`"
          >
            {{ item.word }}
          </span>
          <div v-if="!hotWords.length && !loading" class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-sm text-slate-500">
            暂无封禁热词
          </div>
        </div>
      </SurfacePanel>

      <SurfacePanel>
        <div class="mb-4">
          <h2 class="ss-section-title">被封玩家排行</h2>
          <p class="ss-section-copy">选出出现次数最多的玩家，用来辅助追踪重复风险来源。</p>
        </div>
        <div ref="rankChartRef" class="h-[300px]"></div>
        <div v-if="!playerRank.length && !loading" class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-center text-sm text-slate-500">
          暂无封禁玩家数据
        </div>
      </SurfacePanel>
    </div>

    <SurfacePanel class="mt-6" tone="muted">
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 class="ss-section-title">{{ listTitle }}</h2>
          <p class="ss-section-copy">当你输入关键词时，这里会切换为高亮检索结果。</p>
        </div>
        <div class="flex items-center gap-2">
          <span v-if="highlightLoading" class="ss-chip">检索中</span>
          <span class="ss-chip">{{ filteredList.length }} 条</span>
        </div>
      </div>

      <div class="grid grid-cols-[120px_minmax(0,1fr)_160px_76px_160px] gap-3 border-b border-white/10 px-1 pb-3 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-500">
        <span>玩家 ID</span>
        <span>封禁内容</span>
        <span>命中词</span>
        <span>风险</span>
        <span>时间</span>
      </div>

      <div class="ss-scrollbar max-h-[520px] overflow-y-auto pr-1">
        <div
          v-for="item in filteredList"
          :key="item.id"
          class="grid grid-cols-[120px_minmax(0,1fr)_160px_76px_160px] items-start gap-3 border-b border-white/8 px-1 py-3 text-sm last:border-0"
        >
          <span class="truncate text-slate-400" :title="item.playerId">{{ item.playerId }}</span>
          <span
            class="leading-6 text-slate-200"
            :class="{ 'text-slate-100': searchMode }"
            :title="item.content"
            v-html="renderContent(item)"
          />
          <span class="truncate text-xs text-slate-400">
            <span v-if="item.hitWords" class="inline-flex rounded-full border border-rose-400/20 bg-rose-400/10 px-2.5 py-1 text-rose-100">
              {{ item.hitWords }}
            </span>
            <span v-else>—</span>
          </span>
          <span class="inline-flex rounded-full border border-rose-400/20 bg-rose-400/10 px-2.5 py-1 text-center text-xs font-semibold text-rose-100">
            {{ item.riskScore ?? '-' }}
          </span>
          <span class="text-xs text-slate-500">{{ formatTime(item.createTime) }}</span>
        </div>

        <div v-if="!filteredList.length && !loading && !highlightLoading" class="px-4 py-10 text-center text-sm text-slate-500">
          没有匹配的封禁消息
        </div>
      </div>
    </SurfacePanel>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import {
  analyzeBlockedMessages,
  searchBlockedMessages,
  searchBlockedMessagesWithHighlight
} from '../api/archive'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'
import { escapeHtml, sanitizeHighlight } from '../utils/escapeHtml'

const REFRESH_INTERVAL_MS = 15000
const FETCH_LIMIT = 500
const HIGHLIGHT_LIMIT = 100
const RANK_TOP_N = 10

const blocked = ref([])
const highlightRows = ref([])
const archiveAnalysis = ref(null)
const loading = ref(false)
const highlightLoading = ref(false)
const filterText = ref('')
const rankChartRef = ref(null)
const platformChartRef = ref(null)
const trendChartRef = ref(null)

let rankChart = null
let platformChart = null
let trendChart = null
let timer = null
let highlightTimer = null
let highlightRequestSeq = 0
let unmounted = false

const STOP_WORDS = new Set([
  '的', '了', '是', '在', '和', '就', '都', '这', '那', '你', '我', '们',
  '她', '他', '我们', '你们', '他们', '一个', '这个', '那个', '还有', '已经',
  '可以', '真的', '就是', '然后', '但是', '因为', '所以', '什么',
  '怎么', '现在', '不要', 'please', 'the', 'and', 'for', 'with', 'that',
  'this', 'from', 'you', 'are', 'have', 'has', 'not', 'but', 'just',
  'doge', 'tv', 'ovo', 'tvt', 'qaq', 'qwq', 'ojbk', '笑哭', '妙啊',
  '微笑', '滑稽', '吃瓜', '热词', '系列', '给心心', '保佑', '抓狂',
  '脱单doge', '辣眼睛', '酸了', '酸成柠檬精'
])

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
    for (const w of hitTokens) counter.set(w, (counter.get(w) || 0) + 3)

    const contentTokens = tokenize(item?.content).filter((w) => w.length >= 2 && !STOP_WORDS.has(w))
    for (const w of contentTokens) counter.set(w, (counter.get(w) || 0) + 1)
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

const platformDistribution = computed(() => {
  const source = archiveAnalysis.value?.platformDistribution || {}
  return Object.entries(source)
    .map(([platform, count]) => ({ platform, count: Number(count || 0) }))
    .filter((item) => item.count > 0)
    .sort((a, b) => b.count - a.count)
})

const timeTrend = computed(() => {
  const source = Array.isArray(archiveAnalysis.value?.timeTrend) ? archiveAnalysis.value.timeTrend : []
  return source
    .map((item) => ({
      time: String(item?.time || ''),
      count: Number(item?.count || 0)
    }))
    .filter((item) => item.time && item.count >= 0)
})

const searchMode = computed(() => filterText.value.trim().length > 0)

const listTitle = computed(() => {
  if (!searchMode.value) return `所有封禁消息（共 ${blocked.value.length} 条）`
  return `高亮检索结果（共 ${highlightRows.value.length} 条）`
})

const statusText = computed(() => {
  if (highlightLoading.value) return '检索中'
  if (searchMode.value) return `命中 ${highlightRows.value.length} 条`
  return `已加载 ${blocked.value.length} 条`
})

const filteredList = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  if (searchMode.value) return highlightRows.value
  if (!kw) return blocked.value
  return blocked.value.filter((item) => {
    return (
      String(item?.playerId || '').toLowerCase().includes(kw) ||
      String(item?.content || '').toLowerCase().includes(kw) ||
      String(item?.hitWords || '').toLowerCase().includes(kw)
    )
  })
})

function normalizeHighlightRow(row) {
  const message = row?.message || {}
  return {
    ...message,
    highlightContent: row?.highlightContent || message.content || '',
    highlights: row?.highlights || {}
  }
}

function renderContent(item) {
  if (searchMode.value) return sanitizeHighlight(item?.highlightContent || item?.content)
  return escapeHtml(item?.content)
}

function wordStyle(item, idx) {
  const count = Number(item?.count || 0)
  const size = Math.max(13, Math.min(32, 12 + Math.log2(count + 1) * 4.5))
  const hue = (idx * 41 + 350) % 360
  const intensity = Math.min(72, 45 + count * 2)
  return {
    fontSize: `${size}px`,
    color: `hsl(${hue} ${intensity}% 68%)`
  }
}

function formatTime(value) {
  if (!value) return '—'
  if (typeof value === 'string') return value
  try {
    const d = parseDateTime(value)
    if (Number.isNaN(d.getTime())) return String(value)
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch (_) {
    return String(value)
  }
}

function parseDateTime(value) {
  if (!value) return new Date(0)
  if (value instanceof Date) return value
  return new Date(String(value).replace(' ', 'T'))
}

function initRankChart() {
  if (!rankChartRef.value) return
  rankChart = echarts.init(rankChartRef.value)
  renderRankChart()
}

function initPlatformChart() {
  if (!platformChartRef.value) return
  platformChart = echarts.init(platformChartRef.value)
  renderPlatformChart()
}

function initTrendChart() {
  if (!trendChartRef.value) return
  trendChart = echarts.init(trendChartRef.value)
  renderTrendChart()
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
    grid: { left: 100, right: 24, top: 16, bottom: 24 },
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
            { offset: 0, color: '#FF6B57' },
            { offset: 1, color: '#F7B955' }
          ]),
          borderRadius: [0, 8, 8, 0]
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

function renderPlatformChart() {
  if (!platformChart) return
  const data = platformDistribution.value
  platformChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#e2e8f0' }
    },
    legend: {
      bottom: 0,
      textStyle: { color: '#94a3b8', fontSize: 11 }
    },
    series: [
      {
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['50%', '44%'],
        data: data.map((item) => ({ name: item.platform, value: item.count })),
        label: {
          color: '#cbd5f5',
          formatter: '{b}\n{c}'
        },
        itemStyle: {
          borderColor: '#020617',
          borderWidth: 2
        },
        color: ['#FF6B57', '#F7B955', '#5EE6FF', '#6C63FF', '#42D392', '#3B82F6']
      }
    ]
  })
}

function renderTrendChart() {
  if (!trendChart) return
  const data = timeTrend.value
  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#e2e8f0' }
    },
    grid: { left: 42, right: 18, top: 22, bottom: 42 },
    xAxis: {
      type: 'category',
      data: data.map((item) => item.time),
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#94a3b8', rotate: data.length > 6 ? 30 : 0 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { lineStyle: { color: '#334155' } },
      splitLine: { lineStyle: { color: '#1e293b' } },
      axisLabel: { color: '#94a3b8' }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: data.map((item) => item.count),
        lineStyle: { width: 3, color: '#FF6B57' },
        itemStyle: { color: '#F7B955' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(255, 107, 87, 0.28)' },
            { offset: 1, color: 'rgba(255, 107, 87, 0.02)' }
          ])
        }
      }
    ]
  })
}

async function loadData() {
  if (loading.value) return
  loading.value = true
  try {
    const [res, analysisRes] = await Promise.all([
      searchBlockedMessages(FETCH_LIMIT),
      analyzeBlockedMessages(10)
    ])
    const list = Array.isArray(res?.data) ? res.data : []
    list.sort((a, b) => {
      const ta = a?.createTime ? parseDateTime(a.createTime).getTime() : 0
      const tb = b?.createTime ? parseDateTime(b.createTime).getTime() : 0
      return tb - ta
    })
    blocked.value = list
    archiveAnalysis.value = analysisRes?.data || null
  } catch (_) {
    // keep last data
  } finally {
    loading.value = false
  }
}

async function loadHighlightData(keyword) {
  const normalizedKeyword = keyword.trim()
  const requestSeq = ++highlightRequestSeq

  if (!normalizedKeyword) {
    highlightRows.value = []
    highlightLoading.value = false
    return
  }

  highlightLoading.value = true
  try {
    const res = await searchBlockedMessagesWithHighlight(normalizedKeyword, HIGHLIGHT_LIMIT)
    if (requestSeq !== highlightRequestSeq) return
    const rows = Array.isArray(res?.data) ? res.data : []
    highlightRows.value = rows.map(normalizeHighlightRow).sort((a, b) => {
      const ta = a?.createTime ? parseDateTime(a.createTime).getTime() : 0
      const tb = b?.createTime ? parseDateTime(b.createTime).getTime() : 0
      return tb - ta
    })
  } catch (_) {
    if (requestSeq === highlightRequestSeq) highlightRows.value = []
  } finally {
    if (requestSeq === highlightRequestSeq) highlightLoading.value = false
  }
}

function resizeChart() {
  if (rankChart) rankChart.resize()
  if (platformChart) platformChart.resize()
  if (trendChart) trendChart.resize()
}

watch(playerRank, () => {
  renderRankChart()
})

watch(platformDistribution, () => {
  renderPlatformChart()
})

watch(timeTrend, () => {
  renderTrendChart()
})

watch(filterText, (value) => {
  if (highlightTimer) {
    clearTimeout(highlightTimer)
    highlightTimer = null
  }

  const keyword = value.trim()
  if (!keyword) {
    highlightRequestSeq += 1
    highlightRows.value = []
    highlightLoading.value = false
    return
  }

  highlightTimer = setTimeout(() => {
    loadHighlightData(keyword)
  }, 260)
})

onMounted(async () => {
  await nextTick()
  initPlatformChart()
  initTrendChart()
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
  if (highlightTimer) clearTimeout(highlightTimer)
  if (rankChart) {
    rankChart.dispose()
    rankChart = null
  }
  if (platformChart) {
    platformChart.dispose()
    platformChart = null
  }
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
  window.removeEventListener('resize', resizeChart)
})
</script>

<style scoped>
:deep(mark) {
  display: inline;
  border-radius: 4px;
  padding: 0 2px;
  background: rgba(250, 204, 21, 0.26);
  color: #fde68a;
  box-shadow: 0 0 12px rgba(250, 204, 21, 0.14);
}
</style>

